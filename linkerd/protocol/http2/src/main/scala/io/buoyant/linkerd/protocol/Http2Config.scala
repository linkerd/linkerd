package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.http2.{Request, Response}
import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.router.{Http2, RoutingFactory}

class Http2Initializer extends ProtocolInitializer.Simple {
  val name = "h2"
  val configClass = classOf[Http2Config]

  protected type Req = Request
  protected type Rsp = Response

  protected val defaultRouter = {
    val pathStack = Http2.router.pathStack
    // .prepend(Headers.Dst.PathFilter.module)
    // .replace(StackClient.Role.prepFactory, DelayedRelease.module)
    // .prepend(http.ErrorResponder.module)
    val boundStack = Http2.router.boundStack
    // .prepend(Headers.Dst.BoundFilter.module)
    val clientStack = Http2.router.clientStack
    // .prepend(http.AccessLogger.module)
    // .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
    // .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
    // .insertAfter(StackClient.Role.prepConn, Headers.Ctx.clientModule)
    Http2.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
      .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  protected val defaultServer = Http2.server

  override def defaultServerPort: Int = 4142
}

object Http2Initializer extends Http2Initializer

class Http2Config extends RouterConfig {

  var client: Option[ClientConfig] = None
  var servers: Seq[ServerConfig] = Nil

  // @JsonIgnore
  // override def baseResponseClassifier = ...

  @JsonIgnore
  override val protocol: ProtocolInitializer = Http2Initializer
}
