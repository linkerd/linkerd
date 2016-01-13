package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import io.buoyant.router.{Http, RoutingFactory}
import io.buoyant.router.http.AccessLogger

class HttpInitializer extends ProtocolInitializer.Simple {
  val name = "http"

  protected type Req = com.twitter.finagle.http.Request
  protected type Rsp = com.twitter.finagle.http.Response

  protected val defaultRouter = Http.router
    .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

  protected val defaultServer = Http.server
    .configured(Server.Port(4140))

  /*
   * Router params
   */

  val AccessLog = Parsing.Param.Text("httpAccessLog") { f =>
    AccessLogger.param.File(f)
  }

  val UriInDst = Parsing.Param.Boolean("httpUriInDst") { uriInDst =>
    Http.param.UriInDst(uriInDst)
  }

  override protected val routerParamsParser = Parsing.Params(
    AccessLog,
    UriInDst
  )
}
