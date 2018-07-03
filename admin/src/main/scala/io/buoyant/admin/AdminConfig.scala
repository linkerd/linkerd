package io.buoyant.admin

import com.twitter.finagle.buoyant.TlsServerConfig
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import java.net.{InetAddress, InetSocketAddress}
import io.buoyant.config.types.Port

case class AdminConfig(
  ip: Option[InetAddress] = None,
  port: Option[Port] = None,
  shutdownGraceMs: Option[Int] = None,
  security: Option[AdminSecurityConfig] = None,
  tls: Option[TlsServerConfig] = None,
  httpIdentifierPort: Option[Port] = None,
  workerThreads: Option[Int] = None
) {

  def mk(defaultAddr: InetSocketAddress, stats: StatsReceiver): Admin = {
    val adminIp = ip.getOrElse(defaultAddr.getAddress)
    val adminPort = port.map(_.port).getOrElse(defaultAddr.getPort)
    val addr = new InetSocketAddress(adminIp, adminPort)
    new Admin(addr, tls, workerThreads.getOrElse(2), stats, security)
  }
}

object AdminSecurityConfig {
  private val log = Logger.get(Admin.label)
}

case class AdminSecurityConfig(
  uiEnabled: Option[Boolean] = Some(true),
  controlEnabled: Option[Boolean] = Some(true),
  diagnosticsEnabled: Option[Boolean] = Some(true),
  pathWhitelist: Option[List[String]] = None,
  pathBlacklist: Option[List[String]] = None
) {

  def mkFilter(): SecurityFilter = {
    var securityFilter = SecurityFilter()
      .withUiEndpoints(uiEnabled.getOrElse(true))
      .withControlEndpoints(controlEnabled.getOrElse(true))
      .withDiagnosticsEndpoints(diagnosticsEnabled.getOrElse(true))

    securityFilter = pathWhitelist match {
      case Some(elems) =>
        elems.foldLeft(securityFilter) { (f, elem) => f.withWhitelistedElement(elem) }
      case None =>
        securityFilter
    }
    securityFilter = pathBlacklist match {
      case Some(elems) =>
        elems.foldLeft(securityFilter) { (f, elem) => f.withBlacklistedElement(elem) }
      case None =>
        securityFilter
    }
    AdminSecurityConfig.log.debug("Created admin security filter %s", securityFilter)
    securityFilter
  }
}
