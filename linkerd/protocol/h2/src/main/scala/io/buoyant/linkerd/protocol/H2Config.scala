package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.{JsonParser, TreeNode}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonNode}
import com.twitter.conversions.storage._
import com.twitter.conversions.time._
import com.twitter.finagle.buoyant.h2.{param => h2Param, _}
import com.twitter.finagle.buoyant.h2.param._
import com.twitter.finagle.buoyant.h2.service.H2Classifier
import com.twitter.finagle.buoyant.{ParamsMaybeWith, PathMatcher}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.filter.DtabStatsFilter
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.{ServiceFactory, Stack, param}
import com.twitter.util.Monitor
import io.buoyant.config.PolymorphicConfig
import io.buoyant.linkerd.protocol.h2.{H2ClassifierConfig, H2LoggerConfig}
import io.buoyant.router.h2.ClassifiedRetries.{BufferSize, ClassificationTimeout}
import io.buoyant.router.h2.{ClassifiedRetryFilter, DupRequest}
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
      .insertAfter(ClassifiedRetries.role, DupRequest.module)
      .prepend(LinkerdHeaders.Dst.PathFilter.module)
      .replace(StackClient.Role.prepFactory, DelayedRelease.module)

    val boundStack = H2.router.boundStack
      .prepend(LinkerdHeaders.Dst.BoundFilter.module)

    val clientStack = H2.router.clientStack
      .replace(H2TraceInitializer.role, H2TraceInitializer.clientModule)
      .insertAfter(StackClient.Role.prepConn, LinkerdHeaders.Ctx.clientModule)
      .insertAfter(DtabStatsFilter.role, H2LoggerConfig.module)

    //  .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)

    H2.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
  }

  private[this] val monitor = Monitor.mk { case NonFatal(_) => true }

  protected val defaultServer = {
    val stk = H2.server.stack
      .replace(H2TraceInitializer.role, H2TraceInitializer.serverModule)
      .prepend(LinkerdHeaders.Ctx.serverModule)
      .prepend(h2.ErrorReseter.module)

    H2.server.withStack(stk)
      .configured(param.Monitor(monitor))
  }

  override def clearServerContext(stk: ServerStack): ServerStack = {
    // Does NOT use the ClearContext module that forcibly clears the
    // context. Instead, we just strip out headers on inbound requests.
    stk.replace(LinkerdHeaders.Ctx.serverModule.role, LinkerdHeaders.Ctx.clearServerModule)
  }

  override def defaultServerPort: Int = 4142
}

object H2Initializer extends H2Initializer

case class H2Config(loggers: Option[Seq[H2LoggerConfig]] = None) extends RouterConfig {

  var client: Option[H2Client] = None
  var service: Option[H2Svc] = None
  var servers: Seq[H2ServerConfig] = Nil

  @JsonDeserialize(using = classOf[H2IdentifierConfigDeserializer])
  var identifier: Option[Seq[H2IdentifierConfig]] = None

  @JsonIgnore
  override val protocol: ProtocolInitializer = H2Initializer

  @JsonIgnore
  private[this] def loggerParam = loggers.map { configs =>
    val loggerStack =
      configs.foldRight[Stack[ServiceFactory[Request, Response]]](nilStack) { (config, next) =>
        config.module.toStack(next)
      }
    H2LoggerConfig.param.Logger(loggerStack)
  }

  @JsonIgnore
  override def routerParams: Stack.Params =
    (super.routerParams + identifierParam).maybeWith(loggerParam)

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

  @JsonIgnore
  override def params(vars: Map[String, String]): Stack.Params =
    withEndpointParams(super.params(vars))
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

  @JsonIgnore
  override val alpnProtocols: Option[Seq[String]] =
    Some(Seq(ApplicationProtocolNames.HTTP_2))

  @JsonIgnore
  override val sslServerEngine = Netty4ServerEngineFactory()

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
