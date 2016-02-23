package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Path
import io.buoyant.linkerd.config.Parser
import io.buoyant.router.{Mux, RoutingFactory}

class MuxInitializer extends ProtocolInitializer.Simple {
  val name = "mux"

  protected type Req = com.twitter.finagle.mux.Request
  protected type Rsp = com.twitter.finagle.mux.Response

  protected val defaultRouter = Mux.router
    .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

  protected val defaultServer = Mux.server

  override def defaultServerPort: Int = 4141

  val configClass = classOf[MuxConfig]
}

object MuxInitializer extends MuxInitializer

class MuxConfig extends RouterConfig {

  var servers: Seq[ServerConfig] = Nil
  var client: Option[ClientConfig] = None

  @JsonIgnore
  override def protocol = MuxInitializer
}
