package io.buoyant.linkerd
package protocol

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.fasterxml.jackson.annotation.JsonIgnore
import io.buoyant.linkerd.protocol.h2.ResponseClassifiers
import io.buoyant.router.{H2, ClassifiedRetries, RoutingFactory}
import io.netty.handler.ssl.ApplicationProtocolNames

class H2Initializer extends ProtocolInitializer.Simple {
  val name = "h2"
  val configClass = classOf[H2Config]
  override val experimentalRequired = true

  protected type Req = Request
  protected type Rsp = Response

  protected val defaultRouter = {

    val pathStack = H2.router.pathStack
      // retries can't share header mutations
      .insertAfter(ClassifiedRetries.role, h2.DupRequest.module)
      .prepend(h2.ErrorResponder.module)
    //   .prepend(Headers.Dst.PathFilter.module)
    //   .replace(StackClient.Role.prepFactory, DelayedRelease.module)

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

  protected val defaultServer = {
    val stk = H2.server.stack
      .prepend(h2.ErrorResponder.module)
    H2.server.withStack(stk)
  }

  override def defaultServerPort: Int = 4142
}

object H2Initializer extends H2Initializer

class H2Config extends RouterConfig {

  var client: Option[ClientConfig] = None
  var servers: Seq[H2ServerConfig] = Nil

  @JsonIgnore
  override def baseResponseClassifier =
    ResponseClassifiers.NonRetryableServerFailures
      .orElse(super.baseResponseClassifier)

  // TODO: basic + gRPC (trailers-aware)
  @JsonIgnore
  override def responseClassifier =
    ResponseClassifiers.NonRetryableStream(super.responseClassifier)

  @JsonIgnore
  override val protocol: ProtocolInitializer = H2Initializer
}

class H2ServerConfig extends ServerConfig {

  @JsonIgnore
  override val alpnProtocols: Option[Seq[String]] =
    Some(Seq(ApplicationProtocolNames.HTTP_2))
}
