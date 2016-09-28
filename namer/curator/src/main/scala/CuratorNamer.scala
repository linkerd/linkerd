package io.buoyant.namer.curator

import java.net.URL
import java.util.concurrent.TimeUnit._
import com.fasterxml.jackson.databind.ObjectMapper
import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.x.discovery.{ServiceType, ServiceInstance, ServiceDiscoveryBuilder}
import org.apache.curator.x.discovery.details.InstanceSerializer

import scala.collection.JavaConverters._

class CuratorNamer(zookeeperConnectionString: String, baseZnodePath: String) extends Namer {

  private val log = Logger.get(getClass.getName)

  val curator = CuratorFrameworkFactory.newClient(zookeeperConnectionString, new ExponentialBackoffRetry(1000, 3))
  curator.start
  curator.blockUntilConnected(10, SECONDS)

  val objectMapper = new ObjectMapper()

  val serviceDiscovery = ServiceDiscoveryBuilder.builder(classOf[Void]).basePath(baseZnodePath).serializer(new InstanceSerializer[Void] {

    override def serialize(instance: ServiceInstance[Void]): Array[Byte] = Array[Byte](0)

    override def deserialize(bytes: Array[Byte]): ServiceInstance[Void] = {
      val jsonNode = objectMapper.readTree(bytes)

      val rawServiceInstance = new ServiceInstance[Void](
        jsonNode.get(Curator.NAME).asText,
        jsonNode.get(Curator.ID).asText,
        jsonNode.get(Curator.ADDRESS).asText,
        if (!jsonNode.get(Curator.PORT).isNull) jsonNode.get(Curator.PORT).asInt else null,
        if (!jsonNode.get(Curator.SSL_PORT).isNull) jsonNode.get(Curator.SSL_PORT).asInt else null,
        null, // TODO If payload exists return map
        jsonNode.get(Curator.REG_TIME).asLong,
        ServiceType.DYNAMIC,
        null
      )

      return rawServiceInstance;
    }
  }).client(curator).build

  serviceDiscovery.start

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val pathString = path.drop(1).show.substring(1)
    val regex = "([\\w\\.]+)\\.(\\w+):(\\d+)".r
    val result = regex.findFirstMatchIn(pathString)
    var serviceName = result.get.group(1).replace('.', ':') // US SPECIFIC character replacement

    // DEBUG
    if (!result.isEmpty) {
      println(s"SERVICE NAME: ${result.get.group(1)}")
      println(s"TLD: ${result.get.group(2)}")
      println(s"PORT: ${result.get.group(3)}")
    }

    //    serviceDiscovery.queryForInstances(serviceName)
    val serviceCache = serviceDiscovery.serviceCacheBuilder().name(serviceName).build();
    // TODO What happens when there are no instances?
    serviceCache.start()

    // TODO Register a callback to update the NameTree

    var ssl = false;

    val addrs = serviceCache.getInstances.asScala.map((instance: ServiceInstance[Void]) => {
      val address = instance.getAddress
      if (instance.getAddress.startsWith("http")) { // US SPECIFIC
        val url = new URL(instance.getAddress)
        if (address.startsWith("https")) ssl = true;
        Address(url.getHost, url.getPort)
      } else { // GENERAL SOLUTION
        var port = 0
        if (instance.getSslPort != null) {
          ssl = true
          port = instance.getSslPort
        } else {
          port = instance.getPort
        }
        Address(instance.getAddress, port)
      }
    })

    val metadata = Addr.Metadata(("ssl", ssl))
    return Activity.value(NameTree.Leaf(Name.Bound(Var(Addr.Bound(addrs.toSet, metadata)), path, path)))
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

