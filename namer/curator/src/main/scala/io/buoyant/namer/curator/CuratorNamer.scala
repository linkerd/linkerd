package io.buoyant.namer.curator

import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.x.discovery.details.ServiceCacheListener
import org.apache.curator.x.discovery.{ServiceDiscovery, ServiceDiscoveryBuilder, ServiceInstance}
import scala.jdk.CollectionConverters._

/**
 * The CuratorNamer takes paths of the form
 *
 * {{{
 * /<servicename>/<residual>*
 * }}}
 *
 * And uses the Curator discovery library to resolve that service name.
 */
class CuratorNamer(zkConnect: String, basePath: String, idPrefix: Path) extends Namer {

  private[this] val log = Logger.get(getClass.getName)

  private[this] lazy val curator: Future[CuratorFramework] = {
    val client = CuratorFrameworkFactory.newClient(zkConnect, new ExponentialBackoffRetry(1000, 3))
    client.start()

    val promise = new Promise[CuratorFramework]()
    val listenable = client.getConnectionStateListenable
    listenable.addListener(new ConnectionStateListener {
      override def stateChanged(
        client: CuratorFramework,
        newState: ConnectionState
      ): Unit = if (newState.isConnected) {
        log.info("Curator connection established")
        promise.setValue(client)
        listenable.removeListener(this)
      }
    })

    promise
  }

  private[this] lazy val serviceDiscovery: Future[ServiceDiscovery[AnyRef]] =
    curator.map { client =>
      val disco = ServiceDiscoveryBuilder.builder(classOf[AnyRef]).basePath(basePath)
        .client(client).build
      disco.start()
      log.info("Curator service discovery started")
      disco
    }

  protected def isSSL(instance: ServiceInstance[AnyRef]): Boolean =
    instance.getSslPort != null

  protected def getAddress(instance: ServiceInstance[AnyRef]): Address = {
    val port = if (isSSL(instance)) {
      instance.getSslPort
    } else {
      instance.getPort
    }
    Address(instance.getAddress, port)
  }

  protected def getServiceName(path: Path): String = {
    val Path.Utf8(serviceName, rest@_*) = path
    serviceName
  }

  protected def getResidual(path: Path): Path = path.drop(1)

  override def lookup(path: Path): Activity[NameTree[Name]] = {

    val serviceName = getServiceName(path)
    val serviceCache = serviceDiscovery.map { disco =>
      val cache = disco.serviceCacheBuilder().name(serviceName).build()
      cache.start()
      log.info("Curator service cache started for %s", serviceName)
      cache
    }

    Activity.future(serviceCache).flatMap { cache =>

      val instances = cache.getInstances.asScala
      val ssl = instances.exists(isSSL)

      val addrs = cache.getInstances.asScala.map(getAddress)

      val metadata = Addr.Metadata(("ssl", ssl))
      val addrInit = Addr.Bound(addrs.toSet, metadata)

      val addrVar = Var.async(addrInit) { update =>

        val listener = new ServiceCacheListener {
          override def cacheChanged(): Unit = {
            val ssl = instances.exists(isSSL)
            val addrs = cache.getInstances.asScala.map(getAddress)

            val metadata = Addr.Metadata(("ssl", ssl))
            update() = Addr.Bound(addrs.toSet, metadata)
          }

          override def stateChanged(client: CuratorFramework, newState: ConnectionState): Unit = {}
        }

        cache.addListener(listener)

        Closable.make { _ =>
          cache.removeListener(listener)
          Future.Unit
        }
      }

      val residual = getResidual(path)
      val id = idPrefix ++ path.take(path.size - residual.size)
      // TODO: should return NameTree.Neg if service does not exist
      // Curator doesn't seem to differentiate between a service that doesn't
      // exist and a service with an empty instance set.
      Activity.value(NameTree.Leaf(Name.Bound(addrVar, id, residual)))
    }
  }
}
