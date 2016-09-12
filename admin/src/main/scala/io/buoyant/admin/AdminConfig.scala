package io.buoyant.admin

import io.buoyant.config.types.Port
import java.net.InetSocketAddress

case class AdminConfig(port: Port) {

  def mk(): Admin =
    new Admin(new InetSocketAddress(port.port))
}
