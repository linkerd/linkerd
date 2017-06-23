package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service._
import com.twitter.util.Closable
import io.buoyant.namer.{DefaultInterpreterConfig, InterpreterConfig}
import io.buoyant.router.{ClassifiedRetries, Originator, RoutingFactory}

/**
 * A router configuration builder api.
 *
 * Each router must have a [[ProtocolInitializer protocol]] that
 * assists in the parsing and initialization of a router and its
 * services.
 *
 * `params` contains all params configured on this router, including
 * (in order of ascending preference):
 *  - protocol-specific default router parameters
 *  - linker default parameters
 *  - router-specific params.
 *
 * Each router must have one or more [[Server Servers]].
 *
 * Concrete implementations are provided by a [[ProtocolInitializer]].
 */
trait Router {
  def protocol: ProtocolInitializer

  // configuration
  def params: Stack.Params

  protected def _withParams(ps: Stack.Params): Router

  protected def configureServer(s: Server): Server

  def withParams(ps: Stack.Params): Router = {
    val routerWithParams = _withParams(ps)
    routerWithParams.withServers(routerWithParams.servers.map(routerWithParams.configureServer))
  }

  def configured[P: Stack.Param](p: P): Router = withParams(params + p)
  def configured(ps: Stack.Params): Router = withParams(params ++ ps)

  // helper aliases
  def label: String = params[param.Label].label

  // servers
  def servers: Seq[Server]
  protected def withServers(servers: Seq[Server]): Router

  /** Return a router with an additional server. */
  def serving(s: Server): Router =
    withServers(servers :+ configureServer(s))

  def serving(ss: Seq[Server]): Router = ss.foldLeft(this)(_ serving _)

  def withAnnouncers(announcers: Seq[(Path, Announcer)]): Router

  /**
   * Initialize a router by instantiating a downstream router client
   * so that its upstream `servers` may be bound.
   */
  def initialize(): Router.Initialized

  def interpreter: NameInterpreter = params[DstBindingFactory.Namer].interpreter
}

object Router {
  /**
   * A [[Router]] that has been configured and initialized.
   *
   * Concrete implementations
   */
  trait Initialized extends Closable {
    def protocol: ProtocolInitializer
    def params: Stack.Params
    def servers: Seq[Server.Initializer]
    def announcers: Seq[(Path, Announcer)]
  }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "protocol")
trait RouterConfig {

  // RouterConfig subtypes are required to implement these so that they may
  // refine to more specific config types.
  def servers: Seq[ServerConfig]
  def service: Option[Svc]
  def client: Option[Client]

  var dtab: Option[Dtab] = None
  var originator: Option[Boolean] = None
  var dstPrefix: Option[String] = None

  @JsonProperty("announcers")
  var _announcers: Option[Seq[AnnouncerConfig]] = None

  @JsonProperty("label")
  var _label: Option[String] = None

  @JsonIgnore
  def label = _label.getOrElse(protocol.name)

  /*
   * interpreter controls how names are bound.
   */

  @JsonProperty("interpreter")
  var _interpreter: Option[InterpreterConfig] = None

  protected[this] def defaultInterpreter: InterpreterConfig =
    new DefaultInterpreterConfig

  @JsonIgnore
  def interpreter: InterpreterConfig =
    _interpreter.getOrElse(defaultInterpreter)

  /*
   * bindingTimeoutMs limits name resolution.
   */

  @JsonProperty("bindingTimeoutMs")
  var _bindingTimeoutMs: Option[Int] = None

  @JsonIgnore
  def bindingTimeout = _bindingTimeoutMs.map(_.millis).getOrElse(10.seconds)

  /*
   * binding cache size
   */
  var bindingCache: Option[BindingCacheConfig] = None

  @JsonIgnore protected[this] def defaultResponseClassifier: ResponseClassifier =
    ClassifiedRetries.Default

  /**
   * This property must be set to true in order to use this router if it
   * is experimental.
   */
  @JsonProperty("experimental")
  var _experimentalEnabled: Option[Boolean] = None

  /**
   * If this protocol is experimental but has not set the
   * `experimental` property.
   */
  @JsonIgnore
  def disabled = protocol.experimentalRequired && !_experimentalEnabled.contains(true)

  @JsonIgnore
  def routerParams = (Stack.Params.empty +
    param.ResponseClassifier(defaultResponseClassifier) +
    FailureAccrualConfig.default)
    .maybeWith(dtab.map(dtab => RoutingFactory.BaseDtab(() => dtab)))
    .maybeWith(originator.map(Originator.Param(_)))
    .maybeWith(dstPrefix.map(pfx => RoutingFactory.DstPrefix(Path.read(pfx))))
    .maybeWith(bindingCache.map(_.capacity))
    .maybeWith(client.map(_.clientParams))
    .maybeWith(service.map(_.pathParams))
    .maybeWith(bindingCache.flatMap(_.idleTtl)) +
    param.Label(label) +
    DstBindingFactory.BindingTimeout(bindingTimeout)

  @JsonIgnore
  def router(params: Stack.Params): Router = {
    val prms = params ++ routerParams
    val param.Label(label) = prms[param.Label]
    val announcers = _announcers.toSeq.flatten.map { announcer =>
      announcer.prefix -> announcer.mk
    }
    protocol.router.configured(prms)
      .serving(servers.map(_.mk(protocol, label)))
      .withAnnouncers(announcers)
  }

  @JsonIgnore
  def protocol: ProtocolInitializer
}

case class BindingCacheConfig(
  paths: Option[Int],
  trees: Option[Int],
  bounds: Option[Int],
  clients: Option[Int],
  idleTtlSecs: Option[Int]
) {
  private[this] val default = DstBindingFactory.Capacity.default

  def capacity = DstBindingFactory.Capacity(
    paths = paths.getOrElse(default.paths),
    trees = trees.getOrElse(default.trees),
    bounds = bounds.getOrElse(default.bounds),
    clients = clients.getOrElse(default.clients)
  )

  def idleTtl: Option[DstBindingFactory.IdleTtl] = idleTtlSecs.map { t =>
    DstBindingFactory.IdleTtl(t.seconds)
  }
}

