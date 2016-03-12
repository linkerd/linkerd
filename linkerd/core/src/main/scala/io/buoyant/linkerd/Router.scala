package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.fasterxml.jackson.core.{io => _}
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.service.{FailFastFactory, TimeoutFilter}
import com.twitter.util.Closable
import io.buoyant.namer.{InterpreterConfig, DefaultInterpreterConfig}
import io.buoyant.router.RoutingFactory

/**
 * A router configuration builder api.
 *
 * Each router must have a [[ProtocolInitializer protocol]] that
 * assists in the parsing and intialization of a router and its
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

  def withParams(ps: Stack.Params): Router = {
    val r = _withParams(ps)
    // Copy stats and tracing params from router to servers
    val param.Stats(stats) = r.params[param.Stats]
    val srvStats = param.Stats(stats.scope(label, "srv"))
    val tracer = r.params[param.Tracer]
    r.withServers(servers.map(_.configured(srvStats).configured(tracer)))
  }

  def configured[P: Stack.Param](p: P): Router = withParams(params + p)
  def configured(ps: Stack.Params): Router = withParams(params ++ ps)

  // helper aliases
  def label: String = params[param.Label].label

  // servers
  def servers: Seq[Server]
  protected def withServers(servers: Seq[Server]): Router

  /** Return a router with an additional server. */
  def serving(s: Server): Router = withServers(servers :+ Router.configureServer(this, s))

  def serving(ss: Seq[Server]): Router = ss.foldLeft(this)(_ serving _)

  /** Return a router with TLS configuration read from the provided config. */
  def withTls(tls: TlsClientConfig): Router

  /**
   * Initialize a router by instantiating a downstream router client
   * so that its upstream `servers` may be bound.
   */
  def initialize(): Router.Initialized
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
  }

  private def configureServer(router: Router, server: Server): Server = {
    val ip = server.ip.getHostAddress
    val port = server.port
    val param.Stats(stats) = router.params[param.Stats]
    val routerLabel = router.label
    server.configured(param.Label(s"$ip/$port"))
      .configured(Server.RouterLabel(routerLabel))
      .configured(param.Stats(stats.scope(routerLabel, "srv")))
      .configured(router.params[param.Tracer])
  }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "protocol")
trait RouterConfig {

  // RouterConfig subtypes are required to implement these so that they may
  // refine to more specific config types.
  def servers: Seq[ServerConfig]
  def client: Option[ClientConfig]

  var baseDtab: Option[Dtab] = None
  var failFast: Option[Boolean] = None
  var timeoutMs: Option[Int] = None
  var dstPrefix: Option[String] = None

  @JsonProperty("label")
  var _label: Option[String] = None

  @JsonIgnore
  def label = _label.getOrElse(protocol.name)

  @JsonProperty("interpreter")
  var _interpreter: Option[InterpreterConfig] = None

  protected[this] def defaultInterpreter: InterpreterConfig =
    new DefaultInterpreterConfig

  @JsonIgnore
  def interpreter: InterpreterConfig =
    _interpreter.getOrElse(defaultInterpreter)

  @JsonIgnore
  def routerParams = Stack.Params.empty
    .maybeWith(baseDtab.map(dtab => RoutingFactory.BaseDtab(() => dtab)))
    .maybeWith(failFast.map(FailFastFactory.FailFast(_)))
    .maybeWith(timeoutMs.map(timeout => TimeoutFilter.Param(timeout.millis)))
    .maybeWith(dstPrefix.map(pfx => RoutingFactory.DstPrefix(Path.read(pfx))))
    .maybeWith(client.map(_.clientParams)) + param.Label(label)

  @JsonIgnore
  def router(params: Stack.Params): Router = {
    val prms = params ++ routerParams
    val param.Label(label) = prms[param.Label]
    protocol.router.configured(prms)
      .serving(servers.map(_.mk(protocol, label)))
      .maybeTransform(client.flatMap(_.tls).map(tls => _.withTls(tls)))
  }

  @JsonIgnore
  def protocol: ProtocolInitializer
}

class ClientConfig {

  var tls: Option[TlsClientConfig] = None
  var loadBalancer: Option[LoadBalancerConfig] = None
  var hostConnectionPool: Option[HostConnectionPool] = None

  @JsonIgnore
  def clientParams: Stack.Params = Stack.Params.empty
    .maybeWith(loadBalancer.map(_.clientParams))
    .maybeWith(hostConnectionPool.map(_.param))
}

case class HostConnectionPool(
  minSize: Option[Int],
  maxSize: Option[Int],
  idleTimeMs: Option[Int],
  maxWaiters: Option[Int]
) {
  @JsonIgnore
  private[this] val default = DefaultPool.Param.param.default

  @JsonIgnore
  def param = DefaultPool.Param(
    low = minSize.getOrElse(default.low),
    high = maxSize.getOrElse(default.high),
    bufferSize = 0,
    idleTime = idleTimeMs.map(_.millis).getOrElse(default.idleTime),
    maxWaiters = maxWaiters.getOrElse(default.maxWaiters)
  )
}
