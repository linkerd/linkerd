package io.buoyant.namer.curator

import java.util.concurrent.TimeUnit._
import com.fasterxml.jackson.databind.ObjectMapper
import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.x.discovery.{ServiceType, ServiceInstance, ServiceDiscoveryBuilder}
import org.apache.curator.x.discovery.details.{ServiceCacheListener, InstanceSerializer}

import scala.collection.JavaConverters._

class CuratorNamer(zookeeperConnectionString: String, baseZnodePath: String) extends Namer {

  private val log = Logger.get(getClass.getName)

  val curator = CuratorFrameworkFactory.newClient(zookeeperConnectionString, new ExponentialBackoffRetry(1000, 3))
  curator.start
  curator.blockUntilConnected(10, SECONDS)

  val objectMapper = new ObjectMapper()

  val serviceDiscovery = ServiceDiscoveryBuilder.builder(classOf[Void]).basePath(baseZnodePath).serializer(new InstanceSerializer[Void] {

    override def serialize(instance: ServiceInstance[Void]): Array[Byte] = Array[Byte](0)

    // Completely ignores a Curator service discovery payload object if it exists.
    override def deserialize(bytes: Array[Byte]): ServiceInstance[Void] = {
      val jsonNode = objectMapper.readTree(bytes)

      val rawServiceInstance = new ServiceInstance[Void](
        jsonNode.get(Curator.NAME).asText,
        jsonNode.get(Curator.ID).asText,
        jsonNode.get(Curator.ADDRESS).asText,
        if (!jsonNode.get(Curator.PORT).isNull) jsonNode.get(Curator.PORT).asInt else null,
        if (!jsonNode.get(Curator.SSL_PORT).isNull) jsonNode.get(Curator.SSL_PORT).asInt else null,
        null,
        jsonNode.get(Curator.REG_TIME).asLong,
        ServiceType.DYNAMIC,
        null
      )

      return rawServiceInstance;
    }
  }).client(curator).build

  serviceDiscovery.start

  def isSSL(instance: ServiceInstance[Void]) = {
    (instance.getSslPort != null)
  }

  def getAddress(instance: ServiceInstance[Void]) = {
    val port = if (isSSL(instance)) {
      instance.getSslPort
    } else {
      instance.getPort
    }
    Address(instance.getAddress, port)
  }

  def getServiceName(path: Path): String = {
    val Path.Utf8(serviceName, rest@_*) = path
    serviceName
  }

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val serviceName = getServiceName(path)
    val serviceCache = serviceDiscovery.serviceCacheBuilder().name(serviceName).build();
    serviceCache.start()

    val instances = serviceCache.getInstances.asScala
    val ssl = instances.exists(isSSL)

    val addrs = serviceCache.getInstances.asScala.map((instance: ServiceInstance[Void]) => {
      getAddress(instance)
    })

    val metadata = Addr.Metadata(("ssl", ssl))
    val addrInit = Addr.Bound(addrs.toSet, metadata)

    val addrVar = Var.async(addrInit) { update =>

      val listener = new ServiceCacheListener {

        override def cacheChanged(): Unit = {
          val ssl = instances.exists(isSSL)
          val addrs = serviceCache.getInstances.asScala.map((instance: ServiceInstance[Void]) => {
            getAddress(instance)
          })

          val metadata = Addr.Metadata(("ssl", ssl))
          update() = Addr.Bound(addrs.toSet, metadata)
        }

        override def stateChanged(client: CuratorFramework, newState: ConnectionState): Unit = {}

      }

      serviceCache.addListener(listener)

      Closable.make { deadline =>
        serviceCache.removeListener(listener)
        Future.Unit
      }
    }

    Activity.value(NameTree.Leaf(Name.Bound(addrVar, path, path)))
  }
}

object Curator {
  val NAME = "name"
  val ID = "id"
  val ADDRESS = "address"
  val PORT = "port"
  val SSL_PORT = "sslPort"
  val REG_TIME = "registrationTimeUTC"
  val PAYLOAD = "payload"
}

