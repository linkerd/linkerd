package com.medallia.l5d.curatorsd.announcer

import java.net.InetSocketAddress

import com.medallia.l5d.curatorsd.common.{CuratorSDCommon, ServiceDiscoveryInfo}
import com.medallia.servicediscovery.ServiceDiscoveryRegistrar.RegistrationFormat
import com.medallia.servicediscovery.ServiceId
import com.twitter.finagle.{Announcement, Path}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import io.buoyant.linkerd.FutureAnnouncer

/**
 * Announcer that uses the curator service discovery format.
 * <p>
 * Format used for the service name Path:
 * /#/com.medallia.curatorsd/protocol/[environment]/[tenant]/service_name
 *
 * <ul>
 *   <li>tenant is optional, "_" represents multitenant services
 *   <li>environment is optional, "_" represents global services
 * </ul>
 */
class CuratorSDAnnouncer(zkConnectStr: String, registrationFormat: RegistrationFormat, backwardsCompatibility: Option[String]) extends FutureAnnouncer {

  override val scheme: String = "zk-curator"

  private val log = Logger(getClass)

  val serviceDiscoveryInfo: ServiceDiscoveryInfo = CuratorSDCommon.createServiceDiscovery(zkConnectStr, backwardsCompatibility)

  private def validProtocol(protocol: String): Boolean = protocol == "http" || protocol == "https"

  private def announce(protocol: String, serviceName: String, environment: Option[String], tenant: Option[String], address: InetSocketAddress): Future[Announcement] = {
    val tenantStr = tenant.getOrElse("(multi-tenant)")
    log.info(
      "Announcing %s, protocol: %s, environment: %s, tenant: %s, address: %s, format: %s, ZK cluster: %s",
      serviceName, protocol, environment, tenantStr, address, registrationFormat, zkConnectStr
    )

    if (!validProtocol(protocol))
      throw new IllegalArgumentException(s"Unsupported protocol $protocol")

    val addressHostString = address.getHostString
    val addressPort = address.getPort
    val serviceId = new ServiceId(serviceName, tenant.orNull, environment.orNull)
    val serviceInstance = serviceDiscoveryInfo.serviceDiscovery.register(serviceId, s"$protocol://$addressHostString:$addressPort",
      s"serviceId: $serviceId", true, registrationFormat)

    log.info("Successfully announced id:%s url:%s instance:%s", serviceId, address, serviceInstance)

    Future.value(new Announcement {
      def unannounce() = {
        log.info("Unannouncing %s %s %s %s", protocol, serviceId, address, registrationFormat)
        Future {
          serviceDiscoveryInfo.serviceDiscovery.unregister(serviceId, serviceInstance, registrationFormat)
        }
      }
    })
  }

  override def announceAsync(addr: InetSocketAddress, name: Path): Future[Announcement] = {
    name.take(4) match {
      // TODO (future) full semantic version could be an extra element in the future
      case id@Path.Utf8(protocol, environment, tenant, serviceName) =>
        announce(protocol, serviceName, CuratorSDCommon.fromOptionalPathField(environment), CuratorSDCommon.fromOptionalPathField(tenant), addr)
      case _ =>
        throw new IllegalArgumentException(s"Incorrect number of parts in announcer name (it should be protocol/environment/tenant/serviceName), got $name")
    }
  }

  override def close(deadline: Time) =
    Future {
      log.info("Closing curator announcer %s", zkConnectStr)
      serviceDiscoveryInfo.close()
      log.info("Curator announcer closed")
    }

}
