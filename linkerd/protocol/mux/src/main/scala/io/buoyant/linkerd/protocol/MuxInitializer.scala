package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import io.buoyant.router.{Mux, RoutingFactory}

class MuxInitializer extends ProtocolInitializer.Simple {
  val name = "mux"

  protected type Req = com.twitter.finagle.mux.Request
  protected type Rsp = com.twitter.finagle.mux.Response

  protected val defaultRouter = Mux.router
    .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

  protected val defaultServer = Mux.server
    .configured(Server.Port(4141))
}
