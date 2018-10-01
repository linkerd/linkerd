package io.buoyant.admin

import com.twitter.finagle.buoyant.{SocketOptionsConfig, TlsServerConfig}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import java.net.{InetAddress, InetSocketAddress}
import io.buoyant.config.types.Port

case class AdminConfig(
  ip: Option[InetAddress] = None,
  port: Option[Port] = None,
  socketOptions: Option[SocketOptionsConfig] = None,
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
    new Admin(addr, tls, workerThreads.getOrElse(2), stats, security, socketOptions)
  }
}

object AdminSecurityConfig {
  private val log = Logger.get(Admin.label)
}

case class AdminSecurityConfig(
  uiEnabled: Option[Boolean] = None,
  controlEnabled: Option[Boolean] = None,
  diagnosticsEnabled: Option[Boolean] = None,
  pathWhitelist: Option[List[String]] = None,
  pathBlacklist: Option[List[String]] = None
) {

  def mkFilter(): SecurityFilter = {
    val securityFilter = SecurityFilter(
      uiEnabled = uiEnabled.getOrElse(true),
      controlEnabled = controlEnabled.getOrElse(true),
      diagnosticsEnabled = diagnosticsEnabled.getOrElse(true),
      whitelist = pathWhitelist.getOrElse(Nil).map(_.r),
      blacklist = pathBlacklist.getOrElse(Nil).map(_.r)
    )

    AdminSecurityConfig.log.debug("Created admin security filter %s", securityFilter)
    securityFilter
  }
}
