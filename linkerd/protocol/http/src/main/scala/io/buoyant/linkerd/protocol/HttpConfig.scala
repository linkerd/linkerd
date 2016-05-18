package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import com.twitter.finagle.Http.param.HttpImpl
import com.twitter.finagle.buoyant.linkerd.{DelayedRelease, Headers, HttpTraceInitializer, HttpEngine}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.service.Retries
import io.buoyant.linkerd.protocol.http.{AccessLogger, ResponseClassifiers}
import io.buoyant.router.{Http, RoutingFactory}

class HttpInitializer extends ProtocolInitializer.Simple {
  val name = "http"

  protected type Req = com.twitter.finagle.http.Request
  protected type Rsp = com.twitter.finagle.http.Response

  protected val defaultRouter = {
    val pathStack = Http.router.pathStack
      .prepend(Headers.Dst.PathFilter.module)
      .replace(StackClient.Role.prepFactory, DelayedRelease.module)
      .prepend(http.ErrorResponder.module)
    val boundStack = Http.router.boundStack
      .prepend(Headers.Dst.BoundFilter.module)
    val clientStack = Http.router.clientStack
      .prepend(http.AccessLogger.module)
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
      .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
      .insertAfter(StackClient.Role.prepConn, Headers.Ctx.clientModule)

    Http.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
      .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  protected val defaultServer = {
    val stk = Http.server.stack
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.serverModule)
      .prepend(Headers.Ctx.serverModule)
      .prepend(http.ErrorResponder.module)
      .prepend(http.StatusCodeStatsFilter.module)
    Http.server.withStack(stk)
  }

  val configClass = classOf[HttpConfig]

  override def defaultServerPort: Int = 4140
}

object HttpInitializer extends HttpInitializer

case class HttpClientConfig(
  engine: Option[HttpEngine]
) extends ClientConfig {
  override def clientParams =
    engine.foldLeft(super.clientParams) { (params, engine) => engine.mk(params) }
}

case class HttpServerConfig(
  engine: Option[HttpEngine]
) extends ServerConfig {
  override def serverParams =
    engine.foldLeft(super.serverParams) { (params, engine) => engine.mk(params) }
}

case class HttpConfig(
  httpAccessLog: Option[String],
  identifier: Option[HttpIdentifierConfig]
) extends RouterConfig {

  var client: Option[HttpClientConfig] = None
  var servers: Seq[HttpServerConfig] = Nil

  @JsonIgnore
  override def baseResponseClassifier =
    ResponseClassifiers.NonRetryableServerFailures orElse super.baseResponseClassifier

  @JsonIgnore
  override val protocol: ProtocolInitializer = HttpInitializer

  @JsonIgnore
  override def routerParams: Stack.Params = super.routerParams
    .maybeWith(httpAccessLog.map(AccessLogger.param.File(_)))
    .maybeWith(identifier.map(id => Http.param.HttpIdentifier(id.newIdentifier)))
}
