package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.{JsonParser, TreeNode}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonNode}
import com.twitter.conversions.storage._
import com.twitter.finagle.buoyant.h2.{LinkerdHeaders, Request, Response}
import com.twitter.finagle.buoyant.h2.param._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.{Path, Stack, param}
import com.twitter.util.Monitor
import io.buoyant.config.PolymorphicConfig
import io.buoyant.linkerd.protocol.h2.ResponseClassifiers
import io.buoyant.router.{ClassifiedRetries, H2, RoutingFactory}
import io.netty.handler.ssl.ApplicationProtocolNames
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class H2Initializer extends ProtocolInitializer.Simple {
  val name = "h2"
  val configClass = classOf[H2Config]
  override val experimentalRequired = true

  protected type Req = Request
  protected type Rsp = Response

  protected val defaultRouter = {

    // retries can't share header mutations
    val pathStack = H2.router.pathStack
      .insertAfter(ClassifiedRetries.role, h2.DupRequest.module)
      .prepend(LinkerdHeaders.Dst.PathFilter.module)

    // I think we can safely ignore the DelayedRelease module (as
    // applied by finagle-http), since we don't ever run in
    // FactoryToService mode?
    //
    //   .replace(StackClient.Role.prepFactory, DelayedRelease.module)

    val boundStack = H2.router.boundStack
      .prepend(LinkerdHeaders.Dst.BoundFilter.module)

    val clientStack = H2.router.clientStack
      .insertAfter(StackClient.Role.prepConn, LinkerdHeaders.Ctx.clientModule)

    //  .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
    //  .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)

    H2.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
  }

  private[this] val monitor = Monitor.mk { case NonFatal(_) => true }

  protected val defaultServer = H2.server.withStack(H2.server.stack
    .prepend(LinkerdHeaders.Ctx.serverModule)
    .prepend(h2.ErrorReseter.module))
    .configured(param.Monitor(monitor))

  override def clearServerContext(stk: ServerStack): ServerStack = {
    // Does NOT use the ClearContext module that forcibly clears the
    // context. Instead, we just strip out headers on inbound requests.
    stk.replace(LinkerdHeaders.Ctx.serverModule.role, LinkerdHeaders.Ctx.clearServerModule)
  }

  override def defaultServerPort: Int = 4142
}

object H2Initializer extends H2Initializer

class H2Config extends RouterConfig {

  var client: Option[H2ClientConfig] = None
  var servers: Seq[H2ServerConfig] = Nil

  @JsonDeserialize(using = classOf[H2IdentifierConfigDeserializer])
  var identifier: Option[Seq[H2IdentifierConfig]] = None

  @JsonIgnore
  override def baseResponseClassifier =
    ResponseClassifiers.NonRetryableServerFailures
      .orElse(super.baseResponseClassifier)

  // TODO: gRPC (trailers-aware)
  @JsonIgnore
  override def responseClassifier =
    ResponseClassifiers.NonRetryableStream(super.responseClassifier)

  @JsonIgnore
  override val protocol: ProtocolInitializer = H2Initializer

  @JsonIgnore
  override def routerParams: Stack.Params =
    super.routerParams +
      identifierParam

  private[this] def identifierParam: H2.Identifier = identifier match {
    case None => h2.HeaderTokenIdentifier.param
    case Some(configs) =>
      H2.Identifier { params =>
        val identifiers = configs.map(_.newIdentifier(params))
        RoutingFactory.Identifier.compose(identifiers)
      }
  }
}

trait H2EndpointConfig {

  var initialStreamWindowBytes: Option[Int] = None
  var headerTableBytes: Option[Int] = None
  var maxFrameBytes: Option[Int] = None
  var maxHeaderListBytes: Option[Int] = None
  var windowUpdateRatio: Option[Double] = None

  def withEndpointParams(params: Stack.Params): Stack.Params = params
    .maybeWith(windowUpdateRatio.map(r => FlowControl.WindowUpdateRatio(r.toFloat)))
    .maybeWith(headerTableBytes.map(s => Settings.HeaderTableSize(Some(s.bytes))))
    .maybeWith(initialStreamWindowBytes.map(s => Settings.InitialStreamWindowSize(Some(s.bytes))))
    .maybeWith(maxFrameBytes.map(s => Settings.MaxFrameSize(Some(s.bytes))))
    .maybeWith(maxHeaderListBytes.map(s => Settings.MaxHeaderListSize(Some(s.bytes))))
}

class H2ClientConfig extends ClientConfig with H2EndpointConfig {

  @JsonIgnore
  override def clientParams = withEndpointParams(super.clientParams)
}

class H2ServerConfig extends ServerConfig with H2EndpointConfig {

  var maxConcurrentStreamsPerConnection: Option[Int] = None

  @JsonIgnore
  override val alpnProtocols: Option[Seq[String]] =
    Some(Seq(ApplicationProtocolNames.HTTP_2))

  override def withEndpointParams(params: Stack.Params): Stack.Params = super.withEndpointParams(params)
    .maybeWith(maxConcurrentStreamsPerConnection.map(c => Settings.MaxConcurrentStreams(Some(c.toLong))))

  @JsonIgnore
  override def serverParams = withEndpointParams(super.serverParams)
}

abstract class H2IdentifierConfig extends PolymorphicConfig {

  @JsonIgnore
  def newIdentifier(params: Stack.Params): RoutingFactory.Identifier[Request]
}

class H2IdentifierConfigDeserializer extends JsonDeserializer[Option[Seq[H2IdentifierConfig]]] {
  override def deserialize(
    p: JsonParser,
    _c: DeserializationContext
  ): Option[Seq[H2IdentifierConfig]] = {
    val codec = p.getCodec
    val klass = classOf[H2IdentifierConfig]
    codec.readTree[TreeNode](p) match {
      case n: JsonNode if n.isArray =>
        Some(n.asScala.toList.map(codec.treeToValue(_, klass)))
      case node => Some(Seq(codec.treeToValue(node, klass)))
    }
  }

  override def getNullValue(_c: DeserializationContext): Option[Seq[H2IdentifierConfig]] = None
}
