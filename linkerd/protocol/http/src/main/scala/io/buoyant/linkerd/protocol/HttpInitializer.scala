package io.buoyant.linkerd
package protocol

import com.twitter.finagle.{Path, StackBuilder}
import com.twitter.finagle.buoyant.linkerd._
import io.buoyant.router.{Http, RoutingFactory}

class HttpInitializer extends ProtocolInitializer.Simple {
  val name = "http"

  protected type Req = com.twitter.finagle.http.Request
  protected type Rsp = com.twitter.finagle.http.Response

  protected val defaultRouter = {
    val pathStack = Headers.Dst.PathFilter +: Http.router.pathStack
    val boundStack = Headers.Dst.BoundFilter +: Http.router.boundStack
    val clientStack = {
      val stk = new StackBuilder(Http.router.clientStack)
      stk.push(http.AccessLogger.module)
      stk.push(http.StatusCodeStatsFilter.module)
      stk.result.replace(HttpTraceInitializer.role, HttpTraceInitializer.client)
    }
    Http.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
      .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  protected val defaultServer = {
    val stk = http.ErrorResponder +: Http.server.stack
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.server)
    Http.server.withStack(stk)
      .configured(Server.Port(4140))
  }

  /*
   * Router params
   */

  val AccessLog = Parsing.Param.Text("httpAccessLog")(http.AccessLogger.param.File(_))
  val UriInDst = Parsing.Param.Boolean("httpUriInDst")(Http.param.UriInDst(_))
  // TODO Forwarded

  override protected val routerParamsParser = Parsing.Params(
    AccessLog,
    UriInDst
  )
}
