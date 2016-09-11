package io.buoyant.admin

import io.buoyant.config.types.Port
import java.net.InetSocketAddress

case class AdminConfig(port: Port) {

  def mk(app: com.twitter.app.App, config: Any): Admin = {
    val Port(p) = port
    val isa = new InetSocketAddress(p)
    new Admin(app, isa, config)
  }
}
