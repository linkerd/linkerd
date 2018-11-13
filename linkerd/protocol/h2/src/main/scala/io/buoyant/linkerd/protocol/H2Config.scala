package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.{JsonParser, TreeNode}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonNode}
import com.twitter.conversions.storage._
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.h2.param._
import com.twitter.finagle.buoyant.h2.service.H2Classifier
import com.twitter.finagle.buoyant.h2.{param => h2Param, _}
import com.twitter.finagle.buoyant.{ParamsMaybeWith, PathMatcher}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.filter.DtabStatsFilter
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.tracing.TraceInitializerFilter
import com.twitter.finagle.{ServiceFactory, Stack, param}
import com.twitter.logging.Policy
import com.twitter.util.Monitor
import io.buoyant.config.PolymorphicConfig
import io.buoyant.linkerd.protocol.h2._
import io.buoyant.router.h2.ClassifiedRetries.{BufferSize, ClassificationTimeout}
import io.buoyant.router.h2.{ClassifiedRetryFilter, DupRequest, H2AddForwardedHeader}
import io.buoyant.router.http.ForwardClientCertFilter
import io.buoyant.router.{ClassifiedRetries, H2, RoutingFactory}
import io.netty.handler.ssl.ApplicationProtocolNames
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class H2Initializer extends ProtocolInitializer.Simple {
  val name = "h2"
  val configClass = classOf[H2Config]

  protected type Req = Request
  protected type Rsp = Response

  protected val defaultRouter = {

    // retries can't share header mutations
    val pathStack = H2.router.pathStack
      .insertAfter(ClassifiedRetries.role, DupRequest.module)
      .prepend(LinkerdHeaders.Dst.PathFilter.module)
      .replace(StackClient.Role.prepFactory, DelayedRelease.module)

    val boundStack = H2.router.boundStack
      .prepend(LinkerdHeaders.Dst.BoundFilter.module)

    val clientStack = H2.router.clientStack
      .prepend(h2.H2AccessLogger.module)
      .replace(TraceInitializerFilter.role, H2TracePropagatorConfig.clientModule)
      .insertAfter(StackClient.Role.prepConn, LinkerdHeaders.Ctx.clientModule)
      .insertAfter(DtabStatsFilter.role, H2RequestAuthorizerConfig.module)
      .insertAfter(H2FailureAccrualFactory.role, H2DiagnosticTracer.module)

    //  .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)

    H2.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
  }

  private[this] val monitor = Monitor.mk { case NonFatal(_) => true }

  protected val defaultServer = {
    val stk = H2.server.stack
      .replace(TraceInitializerFilter.role, H2TracePropagatorConfig.serverModule)
      //ErrorReseter must precede LinkerdHeaders in order to clear l5d context from responses.
      .prepend(h2.ErrorReseter.module)
      .prepend(LinkerdHeaders.Ctx.serverModule)
      .insertBefore(H2AddForwardedHeader.module.role, AddForwardedHeaderConfig.module[Request, Response])

    H2.server.withStack(stk)
      .configured(param.Monitor(monitor))
  }

  override def clearServerContext(stk: ServerStack): ServerStack = {
    // Does NOT use the ClearContext module that forcibly clears the
    // context. Instead, we just strip out headers on inbound requests.
    stk.replace(LinkerdHeaders.Ctx.serverModule.role, LinkerdHeaders.Ctx.clearServerModule)
  }

  override def defaultServerPort: Int = 4142

  override protected def configureServer(router: Router, server: Server): Server =
    super.configureServer(router, server)
      .configured(router.params[H2TracePropagatorConfig.Param])
}

object H2Initializer extends H2Initializer

case class H2Config(
  h2AccessLog: Option[String],
  h2AccessLogRollPolicy: Option[String],
  h2AccessLogAppend: Option[Boolean],
  h2AccessLogRotateCount: Option[Int],
  tracePropagator: Option[H2TracePropagatorConfig]
) extends RouterConfig {

  var client: Option[H2Client] = None
  var service: Option[H2Svc] = None
  var servers: Seq[H2ServerConfig] = Nil

  @JsonDeserialize(using = classOf[H2IdentifierConfigDeserializer])
  var identifier: Option[Seq[H2IdentifierConfig]] = None

  @JsonIgnore
  override val protocol: ProtocolInitializer = H2Initializer

  @JsonIgnore
  override def routerParams(params: Stack.Params): Stack.Params =
    (super.routerParams(params) + identifierParam)
      .maybeWith(h2AccessLog.map(H2AccessLogger.param.File.apply))
      .maybeWith(h2AccessLogRollPolicy.map(Policy.parse _ andThen H2AccessLogger.param.RollPolicy.apply))
      .maybeWith(h2AccessLogAppend.map(H2AccessLogger.param.Append.apply))
      .maybeWith(h2AccessLogRotateCount.map(H2AccessLogger.param.RotateCount.apply))
      .maybeWith(tracePropagator.map(tp => H2TracePropagatorConfig.Param(tp.mk(params))))

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
  @JsonDeserialize(contentAs = classOf[java.lang.Double])
  var windowUpdateRatio: Option[Double] = None

  def withEndpointParams(params: Stack.Params): Stack.Params = params
    .maybeWith(windowUpdateRatio.map(r => FlowControl.WindowUpdateRatio(r.toFloat)))
    .maybeWith(headerTableBytes.map(s => Settings.HeaderTableSize(Some(s.bytes))))
    .maybeWith(initialStreamWindowBytes.map(s => Settings.InitialStreamWindowSize(Some(s.bytes))))
    .maybeWith(maxFrameBytes.map(s => Settings.MaxFrameSize(Some(s.bytes))))
    .maybeWith(maxHeaderListBytes.map(s => Settings.MaxHeaderListSize(Some(s.bytes))))
}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[H2DefaultClient]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[H2DefaultClient], name = "io.l5d.global"),
  new JsonSubTypes.Type(value = classOf[H2StaticClient], name = "io.l5d.static")
))
abstract class H2Client extends Client

class H2DefaultClient extends H2Client with DefaultClient with H2ClientConfig

class H2StaticClient(val configs: Seq[H2PrefixConfig]) extends H2Client with StaticClient

class H2PrefixConfig(prefix: PathMatcher) extends PrefixConfig(prefix) with H2ClientConfig

trait H2ClientConfig extends ClientConfig with H2EndpointConfig {
  var forwardClientCert: Option[Boolean] = None
  var requestAuthorizers: Option[Seq[H2RequestAuthorizerConfig]] = None

  @JsonIgnore
  override def params(vars: Map[String, String]): Stack.Params =
    withEndpointParams(super.params(vars))
      .maybeWith(forwardClientCert.map(ForwardClientCertFilter.Enabled))
      .maybeWith(requestAuthorizerParam)

  @JsonIgnore
  private[this] def requestAuthorizerParam = requestAuthorizers.map { configs =>
    val authorizerStack =
      configs.foldRight[Stack[ServiceFactory[Request, Response]]](nilStack) { (config, next) =>
        config.module.toStack(next)
      }
    H2RequestAuthorizerConfig.param.RequestAuthorizer(authorizerStack)
  }
}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[H2DefaultSvc]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[H2DefaultSvc], name = "io.l5d.global"),
  new JsonSubTypes.Type(value = classOf[H2StaticSvc], name = "io.l5d.static")
))
abstract class H2Svc extends Svc

class H2DefaultSvc extends H2Svc with DefaultSvc with H2SvcConfig

class H2StaticSvc(val configs: Seq[H2SvcPrefixConfig]) extends H2Svc with StaticSvc

class H2SvcPrefixConfig(prefix: PathMatcher) extends SvcPrefixConfig(prefix) with H2SvcConfig

trait H2SvcConfig extends SvcConfig {
  /**
   * Override the setter for SvcConfig's `_responseClassifier` field
   * so that we can set `JsonIgnore` on it (and rewire _h2Classifier
   * to the `"responseClassifier"` JSON property).
   *
   * @param r Not used.
   */
  @JsonIgnore
  final override def responseClassifierConfig_=(r: Option[ResponseClassifierConfig]): Unit =
    throw new UnsupportedOperationException(
      "attempt to set HTTP ResponseClassifierConfig on H2SvcConfig!"
    )

  @JsonIgnore
  final override def responseClassifierConfig: Option[ResponseClassifierConfig] =
    throw new UnsupportedOperationException(
      "attempt to access HTTP ResponseClassifierConfig from H2SvcConfig!"
    )

  @JsonProperty("responseClassifier")
  var _h2Classifier: Option[H2ClassifierConfig] = None

  @JsonIgnore
  def h2Classifier: Option[H2Classifier] =
    _h2Classifier.map(_.mk)

  @JsonDeserialize(contentAs = classOf[java.lang.Long])
  var classificationTimeoutMs: Option[Long] = None

  var retryBufferSize: Option[RetryBufferSize] = None

  @JsonIgnore
  override def params(vars: Map[String, String]): Stack.Params =
    super.params(vars)
      .maybeWith(h2Classifier.map(h2Param.H2Classifier(_)))
      .maybeWith(classificationTimeoutMs.map { t => ClassificationTimeout(t.millis) })
      .maybeWith(retryBufferSize.map(_.param))
}

case class RetryBufferSize(
  @JsonDeserialize(contentAs = classOf[java.lang.Long]) requestBytes: Option[Long] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Long]) responseBytes: Option[Long] = None
) {
  def param = BufferSize(
    requestBytes.getOrElse(ClassifiedRetryFilter.DefaultBufferSize),
    responseBytes.getOrElse(ClassifiedRetryFilter.DefaultBufferSize)
  )
}

class H2ServerConfig extends ServerConfig with H2EndpointConfig {

  var maxConcurrentStreamsPerConnection: Option[Int] = None
  var addForwardedHeader: Option[AddForwardedHeaderConfig] = None

  @JsonIgnore
  override val alpnProtocols: Option[Seq[String]] =
    Some(Seq(ApplicationProtocolNames.HTTP_2))

  @JsonIgnore
  override val sslServerEngine = Netty4ServerEngineFactory()

  override def withEndpointParams(params: Stack.Params): Stack.Params = (super.withEndpointParams(params)
    + Settings.MaxConcurrentStreams(maxConcurrentStreamsPerConnection.orElse(Some(1000)).map(_.toLong)))

  @JsonIgnore
  override def serverParams = withEndpointParams(super.serverParams
    + AddForwardedHeaderConfig.Param(addForwardedHeader))
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
