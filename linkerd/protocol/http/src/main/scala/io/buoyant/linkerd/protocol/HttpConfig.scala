package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes, JsonTypeInfo}
import com.fasterxml.jackson.core.{JsonParser, TreeNode}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonNode}
import com.twitter.conversions.storage._
import com.twitter.finagle.buoyant.{ParamsMaybeWith, PathMatcher}
import com.twitter.finagle.buoyant.linkerd.{DelayedRelease, Headers, HttpTraceInitializer}
import com.twitter.finagle.client.{AddrMetadataExtraction, StackClient}
import com.twitter.finagle.filter.DtabStatsFilter
import com.twitter.finagle.http.filter.{ClientDtabContextFilter, ServerDtabContextFilter, StatsFilter}
import com.twitter.finagle.http.{Request, Response, param => hparam}
import com.twitter.finagle.liveness.FailureAccrualFactory
import com.twitter.finagle.service.Retries
import com.twitter.finagle.stack.nilStack
import com.twitter.finagle.{Path, ServiceFactory, Stack, param => fparam}
import com.twitter.logging.Policy
import com.twitter.util.Future
import io.buoyant.linkerd.protocol.http._
import io.buoyant.router.{ClassifiedRetries, Http, RoutingFactory}
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest}
import io.buoyant.router.http.{AddForwardedHeader, ForwardClientCertFilter, TimestampHeaderFilter}
import scala.collection.JavaConverters._

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
      .replace(Headers.Ctx.clientModule.role, Headers.Ctx.clientModule)
      .insertAfter(DtabStatsFilter.role, HttpRequestAuthorizerConfig.module)
      .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
      .insertAfter(AddrMetadataExtraction.Role, RewriteHostHeader.module)
      // ensure the client-stack framing filter is placed below the stats filter
      // so that any malframed responses it fails are counted as errors
      .insertAfter(FailureAccrualFactory.role, FramingFilter.clientModule)
      .insertAfter(FailureAccrualFactory.role, RequestEvaluator.module)
      .remove(ClientDtabContextFilter.role)

    Http.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
  }

  /**
   * Apply the router's codec configuration parameters to a server.
   */
  override protected def configureServer(router: Router, server: Server): Server =
    super.configureServer(router, server)
      .configured(router.params[hparam.MaxChunkSize])
      .configured(router.params[hparam.MaxHeaderSize])
      .configured(router.params[hparam.MaxInitialLineSize])
      .configured(router.params[hparam.MaxRequestSize])
      .configured(router.params[hparam.MaxResponseSize])
      .configured(router.params[hparam.Streaming])
      .configured(router.params[hparam.CompressionLevel])

  protected val defaultServer = {
    val stk = Http.server.stack
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.serverModule)
      .replace(Headers.Ctx.serverModule.role, Headers.Ctx.serverModule)
      .prepend(http.ErrorResponder.module)
      .prepend(http.StatusCodeStatsFilter.module)
      // ensure the server-stack framing filter is placed below the stats filter
      // so that any malframed requests it fails are counted as errors
      .insertAfter(StatsFilter.role, FramingFilter.serverModule)
      .insertBefore(AddForwardedHeader.module.role, AddForwardedHeaderConfig.module[Request, Response])
      .remove(ServerDtabContextFilter.role)

    Http.server.withStack(stk)
  }

  override def clearServerContext(stk: ServerStack): ServerStack = {
    // Does NOT use the ClearContext module that forcibly clears the
    // context. Instead, we just strip out headers on inbound requests.
    stk.remove(HttpTraceInitializer.role)
      .replace(Headers.Ctx.serverModule.role, Headers.Ctx.clearServerModule)
  }

  val configClass = classOf[HttpConfig]

  override def defaultServerPort: Int = 4140
}

object HttpInitializer extends HttpInitializer

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[HttpDefaultClient]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[HttpDefaultClient], name = "io.l5d.global"),
  new JsonSubTypes.Type(value = classOf[HttpStaticClient], name = "io.l5d.static")
))
abstract class HttpClient extends Client

class HttpDefaultClient extends HttpClient with DefaultClient with HttpClientConfig

class HttpStaticClient(val configs: Seq[HttpPrefixConfig]) extends HttpClient with StaticClient

class HttpPrefixConfig(prefix: PathMatcher) extends PrefixConfig(prefix) with HttpClientConfig

trait HttpClientConfig extends ClientConfig {
  var forwardClientCert: Option[Boolean] = None

  @JsonIgnore
  override def params(vars: Map[String, String]): Stack.Params = {
    super.params(vars)
      .maybeWith(forwardClientCert.map(ForwardClientCertFilter.Enabled))
  }
}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true,
  defaultImpl = classOf[HttpDefaultSvc]
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[HttpDefaultSvc], name = "io.l5d.global"),
  new JsonSubTypes.Type(value = classOf[HttpStaticSvc], name = "io.l5d.static")
))
abstract class HttpSvc extends Svc

class HttpDefaultSvc extends HttpSvc with DefaultSvc with HttpSvcConfig

class HttpStaticSvc(val configs: Seq[HttpSvcPrefixConfig]) extends HttpSvc with StaticSvc

class HttpSvcPrefixConfig(prefix: PathMatcher) extends SvcPrefixConfig(prefix) with HttpSvcConfig

trait HttpSvcConfig extends SvcConfig {

  @JsonIgnore
  override def baseResponseClassifier = ClassifiedRetries.orElse(
    ResponseClassifiers.NonRetryableServerFailures,
    super.baseResponseClassifier
  )

  @JsonIgnore
  override def responseClassifier =
    super.responseClassifier.map { classifier =>
      ResponseClassifiers.NonRetryableChunked(
        ResponseClassifiers.HeaderRetryable(classifier)
      )
    }
}

case class HttpServerConfig(
  addForwardedHeader: Option[AddForwardedHeaderConfig],
  timestampHeader: Option[String]
) extends ServerConfig {

  @JsonIgnore
  override def serverParams = {
    super.serverParams +
      AddForwardedHeaderConfig.Param(addForwardedHeader) +
      TimestampHeaderFilter.Param(timestampHeader)
  }
}

// Cribbed from https://gist.github.com/Aivean/6bb90e3942f3bf966608
class HttpIdentifierConfigDeserializer extends JsonDeserializer[Option[Seq[HttpIdentifierConfig]]] {
  override def deserialize(p: JsonParser, ctxt: DeserializationContext): Option[Seq[HttpIdentifierConfig]] = {
    val codec = p.getCodec
    codec.readTree[TreeNode](p) match {
      case n: JsonNode if n.isArray =>
        Some(n.asScala.toList.map(codec.treeToValue(_, classOf[HttpIdentifierConfig])))
      case node => Some(Seq(codec.treeToValue(node, classOf[HttpIdentifierConfig])))
    }
  }

  override def getNullValue(ctxt: DeserializationContext): Option[Seq[HttpIdentifierConfig]] = None
}

case class HttpConfig(
  httpAccessLog: Option[String],
  httpAccessLogRollPolicy: Option[String],
  httpAccessLogAppend: Option[Boolean],
  httpAccessLogRotateCount: Option[Int],
  @JsonDeserialize(using = classOf[HttpIdentifierConfigDeserializer]) identifier: Option[Seq[HttpIdentifierConfig]],
  requestAuthorizers: Option[Seq[HttpRequestAuthorizerConfig]],
  maxChunkKB: Option[Int],
  maxHeadersKB: Option[Int],
  maxInitialLineKB: Option[Int],
  maxRequestKB: Option[Int],
  maxResponseKB: Option[Int],
  streamingEnabled: Option[Boolean],
  compressionLevel: Option[Int]
) extends RouterConfig {

  var client: Option[HttpClient] = None
  var servers: Seq[HttpServerConfig] = Nil
  var service: Option[HttpSvc] = None

  @JsonIgnore
  override val protocol: ProtocolInitializer = HttpInitializer

  @JsonIgnore
  override val defaultResponseClassifier = ResponseClassifiers.NonRetryableChunked(
    ResponseClassifiers.HeaderRetryable(
      ClassifiedRetries.orElse(
        ResponseClassifiers.NonRetryableServerFailures,
        ClassifiedRetries.Default
      )
    )
  )

  @JsonIgnore
  private[this] val loggerParam = requestAuthorizers.map { configs =>
    val authorizerStack =
      configs.foldRight[Stack[ServiceFactory[Request, Response]]](nilStack) { (config, next) =>
        config.module.toStack(next)
      }
    HttpRequestAuthorizerConfig.param.RequestAuthorizer(authorizerStack)
  }

  @JsonIgnore
  private[this] val combinedIdentifier = identifier.map { configs =>
    Http.param.HttpIdentifier { (prefix, dtab) =>
      RoutingFactory.Identifier.compose(configs.map(_.newIdentifier(prefix, dtab)))
    }
  }
  @JsonIgnore
  override def routerParams: Stack.Params = super.routerParams
    .maybeWith(httpAccessLog.map(AccessLogger.param.File.apply))
    .maybeWith(httpAccessLogRollPolicy.map(Policy.parse _ andThen AccessLogger.param.RollPolicy.apply))
    .maybeWith(httpAccessLogAppend.map(AccessLogger.param.Append.apply))
    .maybeWith(httpAccessLogRotateCount.map(AccessLogger.param.RotateCount.apply))
    .maybeWith(loggerParam)
    .maybeWith(combinedIdentifier)
    .maybeWith(maxChunkKB.map(kb => hparam.MaxChunkSize(kb.kilobytes)))
    .maybeWith(maxHeadersKB.map(kb => hparam.MaxHeaderSize(kb.kilobytes)))
    .maybeWith(maxInitialLineKB.map(kb => hparam.MaxInitialLineSize(kb.kilobytes)))
    .maybeWith(maxRequestKB.map(kb => hparam.MaxRequestSize(kb.kilobytes)))
    .maybeWith(maxResponseKB.map(kb => hparam.MaxResponseSize(kb.kilobytes)))
    .maybeWith(streamingEnabled.map(hparam.Streaming(_)))
    .maybeWith(compressionLevel.map(hparam.CompressionLevel(_)))
}
