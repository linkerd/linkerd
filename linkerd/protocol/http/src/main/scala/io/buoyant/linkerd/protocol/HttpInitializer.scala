package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.linkerd.{Headers, HttpTraceInitializer}
import com.twitter.finagle.{Path, Stack, StackBuilder}
import io.buoyant.linkerd.protocol.http.{AccessLogger, ResponseClassifiers}
import io.buoyant.router.{Http, RoutingFactory}
import io.l5d.HttpIdentifierConfig

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
  }

  val configClass = classOf[HttpConfig]

  override def defaultServerPort: Int = 4140
}

object HttpInitializer extends HttpInitializer

case class HttpConfig(
  httpAccessLog: Option[String],
  identifier: Option[HttpIdentifierConfig]
) extends RouterConfig {

  var client: Option[ClientConfig] = None
  var servers: Seq[ServerConfig] = Nil

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
