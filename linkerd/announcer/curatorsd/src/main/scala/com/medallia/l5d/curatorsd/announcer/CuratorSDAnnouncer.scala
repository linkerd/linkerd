package com.medallia.l5d.curatorsd.announcer

import java.net.InetSocketAddress

import com.medallia.l5d.curatorsd.common.{CuratorSDCommon, ServiceDiscoveryInfo}
import com.medallia.servicediscovery.ServiceInstanceInfo
import com.twitter.finagle.{Announcement, Path}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import io.buoyant.linkerd.FutureAnnouncer
import org.apache.curator.x.discovery._

/**
 * Announcer that uses the curator service discovery format.
 * <p>
 * Format used for the service name Path:
 * /#/com.medallia.curatorsd/protocol/[tenant]/service_name
 *
 * tenant is optional, "_" represents multitenant services
 */
class CuratorSDAnnouncer(zkConnectStr: String) extends FutureAnnouncer {

  override val scheme: String = "zk-curator"

  private val log = Logger(getClass)

  val serviceDiscoveryInfo: ServiceDiscoveryInfo = CuratorSDCommon.createServiceDiscovery(zkConnectStr)

  private def validProtocol(protocol: String): Boolean = protocol == "http" || protocol == "https"

  private def announce(protocol: String, serviceId: String, tenant: Option[String], address: InetSocketAddress): Future[Announcement] = {
    val tenantStr = tenant.getOrElse("(multi-tenant)")
    log.info("Announcing %s, protocol: %s, tenant: %s, address: %s, ZK cluster: %s", serviceId, protocol, tenantStr, address, zkConnectStr)

    if (!validProtocol(protocol))
      throw new IllegalArgumentException(s"Unsupported protocol $protocol")

    val serviceFullPath = CuratorSDCommon.getServiceFullPath(serviceId, tenant)
    val addressHostString = address.getHostString
    val addressPort = address.getPort
    val builder = ServiceInstance.builder[ServiceInstanceInfo]
      .name(serviceFullPath)
      .uriSpec(new UriSpec(s"$protocol://$addressHostString:$addressPort"))
      .address(addressHostString)
      .payload(new ServiceInstanceInfo(s"serviceId: $serviceId, tenant: $tenantStr"))
      .serviceType(ServiceType.DYNAMIC)

    if (protocol == "https") {
      builder.sslPort(addressPort)
    } else {
      builder.port(addressPort)
    }

    val serviceInstance = builder.build

    serviceDiscoveryInfo.serviceDiscovery.registerService(serviceInstance)

    log.info("Successfully announced %s %s %s", serviceFullPath, address, serviceInstance.getId)

    Future.value(new Announcement {
      def unannounce() = {
        log.info("Unannouncing %s %s %s %s", protocol, tenantStr, serviceId, address)
        Future {
          serviceDiscoveryInfo.serviceDiscovery.unregisterService(serviceInstance)
        }
      }
    })
  }

  override def announceAsync(addr: InetSocketAddress, name: Path): Future[Announcement] = {
    name.take(3) match {
      // TODO (future) full semantic version could be an extra element in the future
      case id@Path.Utf8(protocol, tenant, serviceName) =>
        announce(protocol, serviceName, cleanupTenant(tenant), addr)
      case _ =>
        throw new IllegalArgumentException(s"Incorrect number of parts in announcer name (it should be protocol/tenant/serviceName), got $name")
    }
  }

  /** Unfortunately, Path doesn't allow empty elements. "_" means multi-tenant */
  private def cleanupTenant(tenant: String): Option[String] =
    Some(tenant)
      .filter(_ != "_")

  override def close(deadline: Time) =
    Future {
      log.info("Closing curator announcer %s", zkConnectStr)
      serviceDiscoveryInfo.close()
      log.info("Curator announcer closed")
    }

}
