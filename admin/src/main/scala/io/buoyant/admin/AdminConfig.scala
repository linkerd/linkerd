package io.buoyant.admin

import io.buoyant.config.types.Port
import java.net.InetSocketAddress

case class AdminConfig(
  port: Option[Port],
  shutdownGraceMs: Option[Int]
) {

  def mk(defaultPort: Port): Admin = {
    val addr = new InetSocketAddress(port.getOrElse(defaultPort).port)
    new Admin(addr)
  }
}
