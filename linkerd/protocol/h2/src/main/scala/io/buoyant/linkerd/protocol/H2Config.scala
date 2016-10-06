package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.router.{H2, RoutingFactory}
import io.netty.handler.ssl.ApplicationProtocolNames

class H2Initializer extends ProtocolInitializer.Simple {
  val name = "h2"
  val configClass = classOf[H2Config]
  override val experimentalRequired = true

  protected type Req = Request
  protected type Rsp = Response

  protected val defaultRouter = {
    val pathStack = H2.router.pathStack
      .prepend(h2.ErrorResponder.module)
    //   .prepend(Headers.Dst.PathFilter.module)
    //   .replace(StackClient.Role.prepFactory, DelayedRelease.module)
    //   // retries can't share header mutations
    //   .insertAfter(RetryFilter.role, DupRequest.module)

    val boundStack = H2.router.boundStack
    //   .prepend(Headers.Dst.BoundFilter.module)

    val clientStack = H2.router.clientStack
    //   .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
    //   .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
    //   .insertAfter(StackClient.Role.prepConn, Headers.Ctx.clientModule)

    H2.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
      .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  protected val defaultServer = H2.server
    .withStack(H2.server.stack
      .prepend(h2.ErrorResponder.module))

  override def defaultServerPort: Int = 4142
}

object H2Initializer extends H2Initializer

class H2Config extends RouterConfig {

  var client: Option[ClientConfig] = None
  var servers: Seq[H2ServerConfig] = Nil

  // TODO: basic + gRPC (trailers-aware)
  // @JsonIgnore
  // override def baseResponseClassifier = ...

  @JsonIgnore
  override val protocol: ProtocolInitializer = H2Initializer
}

class H2ServerConfig extends ServerConfig {

  @JsonIgnore
  override val alpnProtocols: Option[Seq[String]] =
    Some(Seq(ApplicationProtocolNames.HTTP_2))
}
