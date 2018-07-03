package io.buoyant.admin

import com.twitter.finagle.buoyant.TlsServerConfig
import com.twitter.finagle.stats.StatsReceiver
import java.net.{InetAddress, InetSocketAddress}
import io.buoyant.config.types.Port

case class AdminConfig(
  ip: Option[InetAddress] = None,
  port: Option[Port] = None,
  shutdownGraceMs: Option[Int] = None,
  shutdownEnabled: Option[Boolean] = Some(true),
  tls: Option[TlsServerConfig] = None,
  httpIdentifierPort: Option[Port] = None,
  workerThreads: Option[Int] = None
) {

  def mk(defaultAddr: InetSocketAddress, stats: StatsReceiver): Admin = {
    val adminIp = ip.getOrElse(defaultAddr.getAddress)
    val adminPort = port.map(_.port).getOrElse(defaultAddr.getPort)
    val addr = new InetSocketAddress(adminIp, adminPort)
    new Admin(addr, tls, workerThreads.getOrElse(2), stats, shutdownEnabled.getOrElse(true))
  }
}
