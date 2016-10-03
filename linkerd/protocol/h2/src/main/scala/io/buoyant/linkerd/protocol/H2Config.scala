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

  protected type Req = Request
  protected type Rsp = Response

  // TODO
  protected val defaultRouter = {
    // val pathStack = H2.router.pathStack
    //   .prepend(Headers.Dst.PathFilter.module)
    //   .replace(StackClient.Role.prepFactory, DelayedRelease.module)
    //   .prepend(http.ErrorResponder.module)
    // val boundStack = H2.router.boundStack
    //   .prepend(Headers.Dst.BoundFilter.module)
    // val clientStack = H2.router.clientStack
    //   .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
    //   .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
    //   .insertAfter(StackClient.Role.prepConn, Headers.Ctx.clientModule)
    // H2.router
    //   .withPathStack(pathStack)
    //   .withBoundStack(boundStack)
    //   .withClientStack(clientStack)
    //   .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))

    H2.router.configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  protected val defaultServer = H2.server

  override def defaultServerPort: Int = 4142
}

object H2Initializer extends H2Initializer

class H2Config extends RouterConfig {

  var client: Option[ClientConfig] = None
  var servers: Seq[H2ServerConfig] = Nil

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
