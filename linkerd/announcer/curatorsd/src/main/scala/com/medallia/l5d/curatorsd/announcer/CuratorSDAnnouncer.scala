package com.medallia.l5d.curatorsd.announcer

import java.net.InetSocketAddress

import com.medallia.l5d.curatorsd.common.CuratorSDCommon
import com.medallia.servicediscovery.ServiceInstanceInfo
import com.twitter.finagle.{Announcement, Path}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import io.buoyant.linkerd.FutureAnnouncer
import org.apache.curator.x.discovery._

/**
 * Announcer that uses the curator service discovery format.
 * <p>
 * Format used for the service name Path: /#/com.medallia.curatorsd/{full service name}[#{tenant}]
 * tenant is optional. If it's empty or the last part is missing, it will be registered as a multi-tenant service.
 */
class CuratorSDAnnouncer(zkConnectStr: String) extends FutureAnnouncer {

  override val scheme: String = "zk-curator"

  private val log = Logger(getClass)

  val serviceDiscoveryInfo = CuratorSDCommon.createServiceDiscovery(zkConnectStr)

  private def announce(serviceId: String, tenant: Option[String], address: InetSocketAddress): Future[Announcement] = {
    val tenantStr = tenant.getOrElse("(multi-tenant)")
    log.info("Announcing %s, tenant: %s address: %s, ZK cluster: %s", serviceId, tenantStr, address, zkConnectStr)

    val serviceFullPath = CuratorSDCommon.getServiceFullPath(serviceId, tenant)

    // TODO (future) how to specify https? Handle this when we work on the Namer.
    val addressHostString = address.getHostString
    val addressPort = address.getPort
    val builder = ServiceInstance.builder[ServiceInstanceInfo]
      .name(serviceFullPath)
      .uriSpec(new UriSpec(s"http://$addressHostString:$addressPort"))
      .port(addressPort)
      .address(addressHostString)
      .payload(ServiceInstanceInfo(s"serviceId: $serviceId, tenant: $tenantStr"))
      .serviceType(ServiceType.DYNAMIC)

    val serviceInstance = builder.build

    serviceDiscoveryInfo.serviceDiscovery.registerService(serviceInstance)

    log.info("Successfully announced %s %s %s", serviceFullPath, address, serviceInstance.getId)

    Future.value(new Announcement {
      def unannounce() = {
        log.info("Unannouncing %s %s", serviceFullPath, address)
        Future {
          serviceDiscoveryInfo.serviceDiscovery.unregisterService(serviceInstance)
        }
      }
    })
  }

  override def announceAsync(addr: InetSocketAddress, name: Path): Future[Announcement] = {
    name.take(2) match {
      case id@Path.Utf8(serviceDef) =>
        // TODO (future) full semantic version could be a third element in the future
        serviceDef.split("#") match {
          case Array(serviceId) => announce(serviceId, None, addr)
          case Array(serviceId, tenant) => announce(serviceId, Some(tenant).filter(_.trim.nonEmpty), addr)
          case _ => throw new IllegalArgumentException(s"Incorrect number of parts in announcer name (it should be serviceId[#tenant]) $serviceDef")
        }
      case _ => throw new IllegalArgumentException(s"Tenant information is missing in path: $name")
    }

  }

  override def close(deadline: Time) =
    Future {
      log.info("Closing curator announcer %s", zkConnectStr)
      serviceDiscoveryInfo.close()
      log.info("Curator announcer closed")
    }

}
