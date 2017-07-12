package io.buoyant.admin

import io.buoyant.config.types.Port
import java.net.{InetAddress, InetSocketAddress}
import com.twitter.finagle.buoyant.TlsServerConfig

case class AdminConfig(
  ip: Option[InetAddress] = None,
  port: Option[Port] = None,
  shutdownGraceMs: Option[Int] = None,
  tls: Option[TlsServerConfig] = None
) {

  def mk(defaultAddr: InetSocketAddress): Admin = {
    val adminIp = ip.getOrElse(defaultAddr.getAddress)
    val adminPort = port.map(_.port).getOrElse(defaultAddr.getPort)
    val addr = new InetSocketAddress(adminIp, adminPort)
    new Admin(addr, tls)
  }
}
