package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import com.twitter.finagle.buoyant.linkerd.{Headers, HttpTraceInitializer}
import com.twitter.finagle.netty4.http.exp.Netty4Impl
import com.twitter.finagle.service.Retries
import io.buoyant.linkerd.protocol.http.{AccessLogger, ResponseClassifiers}
import io.buoyant.router.{Http, RoutingFactory}

class HttpInitializer extends ProtocolInitializer.Simple {
  val name = "http"

  protected type Req = com.twitter.finagle.http.Request
  protected type Rsp = com.twitter.finagle.http.Response

  protected val defaultRouter = {
    val pathStack = Headers.Dst.PathFilter +: Http.router.pathStack
    val boundStack = Headers.Dst.BoundFilter +: Http.router.boundStack
    val clientStack = (http.AccessLogger.module +: Http.router.clientStack)
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.client)
      .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)

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
  }

  val configClass = classOf[HttpConfig]

  override def defaultServerPort: Int = 4140
}

object HttpInitializer extends HttpInitializer

case class HttpClientConfig(
  netty4: Option[Boolean]
) extends ClientConfig {
  override def clientParams = super.clientParams
    .maybeWith(netty4.collect { case true => Netty4Impl })
}

case class HttpServerConfig(
  netty4: Option[Boolean]
) extends ServerConfig {
  override def serverParams = super.serverParams
    .maybeWith(netty4.collect { case true => Netty4Impl })
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
