package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo, JsonSubTypes}
import com.fasterxml.jackson.core.{io => _}
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.service._
import com.twitter.util.{Closable, Duration}
import io.buoyant.namer.{InterpreterConfig, DefaultInterpreterConfig}
import io.buoyant.router.{ClassifiedRetries, RoutingFactory}

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

  def withParams(ps: Stack.Params): Router =
    _withParams(ps)
      .withServers(servers.map(Router.configureServer(this, _)))

  def configured[P: Stack.Param](p: P): Router = withParams(params + p)
  def configured(ps: Stack.Params): Router = withParams(params ++ ps)

  // helper aliases
  def label: String = params[param.Label].label

  // servers
  def servers: Seq[Server]
  protected def withServers(servers: Seq[Server]): Router

  /** Return a router with an additional server. */
  def serving(s: Server): Router =
    withServers(servers :+ Router.configureServer(this, s))

  def serving(ss: Seq[Server]): Router = ss.foldLeft(this)(_ serving _)

  /** Return a router with TLS configuration read from the provided config. */
  def withTls(tls: TlsClientConfig): Router

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
  }

  private def configureServer(router: Router, server: Server): Server = {
    val ip = server.ip.getHostAddress
    val port = server.port
    val param.Stats(stats) = router.params[param.Stats]
    val routerLabel = router.label
    server.configured(param.Label(s"$ip/$port"))
      .configured(Server.RouterLabel(routerLabel))
      .configured(param.Stats(stats.scope(routerLabel, "srv")))
      .configured(router.params[TimeoutFilter.Param])
      .configured(router.params[param.ResponseClassifier])
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

  /*
   * responseClassifier categorizes responses to determine whether
   * they are failures and if they are retryable.
   */

  @JsonProperty("responseClassifier")
  var _responseClassifier: Option[ResponseClassifierConfig] = None

  @JsonIgnore
  def baseResponseClassifier: ResponseClassifier =
    ResponseClassifier.Default

  @JsonIgnore
  def responseClassifier: ResponseClassifier =
    _responseClassifier.map(_.mk).getOrElse(PartialFunction.empty) orElse baseResponseClassifier

  /**
   * Budgets are mutable and intended to be shared across clients.
   * However, we want to ensure that budgets are not shared across
   * routers, so we install a default default budget in each router's
   * routerParams.  It may be overridden by clientParams.
   */
  @JsonIgnore
  private def defaultBudget: Retries.Budget =
    Retries.Budget(RetryBudget(), Backoff.const(Duration.Zero))

  @JsonIgnore
  def routerParams = (Stack.Params.empty + defaultBudget)
    .maybeWith(baseDtab.map(dtab => RoutingFactory.BaseDtab(() => dtab)))
    .maybeWith(failFast.map(FailFastFactory.FailFast(_)))
    .maybeWith(timeoutMs.map(timeout => TimeoutFilter.Param(timeout.millis)))
    .maybeWith(dstPrefix.map(pfx => RoutingFactory.DstPrefix(Path.read(pfx))))
    .maybeWith(bindingCache.map(_.capacity))
    .maybeWith(client.map(_.clientParams)) +
    param.ResponseClassifier(responseClassifier) +
    param.Label(label) +
    DstBindingFactory.BindingTimeout(bindingTimeout)

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
  var retries: Option[RetriesConfig] = None

  @JsonIgnore
  def clientParams: Stack.Params = Stack.Params.empty
    .maybeWith(loadBalancer.map(_.clientParams))
    .maybeWith(hostConnectionPool.map(_.param))
    .maybeWith(retries.flatMap(_.mkBackoff))
    .maybeWith(retries.flatMap(_.mkBudget))
}

case class RetriesConfig(
  backoff: Option[BackoffConfig] = None,
  budget: Option[RetryBudgetConfig] = None
) {

  @JsonIgnore
  def mkBackoff: Option[ClassifiedRetries.Backoffs] =
    backoff.map(_.mk).map(ClassifiedRetries.Backoffs(_))

  // We use an empty backoff for Retries.Budget, since this informs
  // _requeue_ delay. Requeues are explicitly for Nacks and
  // non-application-level failures, and so we want to reenqueue
  // these as quickly as possible.
  @JsonIgnore
  def mkBudget: Option[Retries.Budget] =
    budget.map { b => Retries.Budget(b.mk, Backoff.const(Duration.Zero)) }
}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY, property = "kind"
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[ConstantBackoffConfig], name = "constant"),
  new JsonSubTypes.Type(value = classOf[JitteredBackoffConfig], name = "jittered")
))
trait BackoffConfig {
  @JsonIgnore
  def mk: Stream[Duration]
}

case class ConstantBackoffConfig(ms: Int) extends BackoffConfig {
  // ms defaults to 0 when not specified
  def mk = Backoff.constant(ms.millis)
}

/** See http://www.awsarchitectureblog.com/2015/03/backoff.html */
case class JitteredBackoffConfig(minMs: Option[Int], maxMs: Option[Int]) extends BackoffConfig {
  def mk = {
    val min = minMs match {
      case Some(ms) => ms.millis
      case None => throw new IllegalArgumentException("'minMs' must be specified")
    }
    val max = maxMs match {
      case Some(ms) => ms.millis
      case None => throw new IllegalArgumentException("'maxMs' must be specified")
    }
    Backoff.decorrelatedJittered(min, max)
  }
}

case class RetryBudgetConfig(
  ttlSecs: Option[Int],
  minRetriesPerSec: Option[Int],
  percentCanRetry: Option[Double]
) {
  @JsonIgnore
  def mk: RetryBudget = {
    val ttl = ttlSecs.map(_.seconds).getOrElse(10.seconds)
    RetryBudget(ttl, minRetriesPerSec.getOrElse(10), percentCanRetry.getOrElse(0.2))
  }
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

case class BindingCacheConfig(
  paths: Option[Int],
  trees: Option[Int],
  bounds: Option[Int],
  clients: Option[Int]
) {
  private[this] val default = DstBindingFactory.Capacity.default

  def capacity = DstBindingFactory.Capacity(
    paths = paths.getOrElse(default.paths),
    trees = trees.getOrElse(default.trees),
    bounds = bounds.getOrElse(default.bounds),
    clients = clients.getOrElse(default.clients)
  )
}
