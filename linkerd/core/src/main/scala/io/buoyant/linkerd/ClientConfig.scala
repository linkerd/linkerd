package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes}
import com.twitter.conversions.time._
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.{DstBindingFactory, PathMatcher, TlsClientPrep}
import com.twitter.finagle.buoyant.TlsClientPrep.{TransportSecurity, Trust}
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.service.{Backoff, Retries, RetryBudget}
import com.twitter.util.Duration
import io.buoyant.config.PolymorphicConfig
import io.buoyant.router.ClassifiedRetries
import scala.util.control.NoStackTrace

trait ClientConfig {

  var tls: Option[TlsClientConfig] = None
  var loadBalancer: Option[LoadBalancerConfig] = None
  var hostConnectionPool: Option[HostConnectionPool] = None
  var retries: Option[RetriesConfig] = None
  var failureAccrual: Option[FailureAccrualConfig] = None

  @JsonIgnore
  def params(vars: Map[String, String]): Stack.Params = Stack.Params.empty
    .maybeWith(tls.map(_.params(vars)))
    .maybeWith(loadBalancer.map(_.clientParams))
    .maybeWith(hostConnectionPool.map(_.param))
    .maybeWith(retries.flatMap(_.mkBackoff))
    .maybeWith(retries.flatMap(_.mkBudget)) +
    FailureAccrualConfig.param(failureAccrual)
}

class ClientConfigImpl extends ClientConfig

case class TlsClientConfig(
  disableValidation: Option[Boolean],
  commonName: Option[String],
  trustCerts: Seq[String] = Nil
) {
  def params(vars: Map[String, String]): Stack.Params = this match {
    case TlsClientConfig(Some(true), _, _) =>
      Stack.Params.empty +
        TransportSecurity(TransportSecurity.Insecure) +
        Trust(Trust.NotConfigured)
    case TlsClientConfig(_, Some(cn), certs) =>
      Stack.Params.empty +
        TransportSecurity(TransportSecurity.Secure()) +
        Trust(Trust.Verified(PathMatcher.substitute(vars, cn), trustCerts.map(TlsClientPrep.loadCert(_))))
    case TlsClientConfig(Some(false) | None, None, _) =>
      val msg = "tls is configured with validation but `commonName` is not set"
      throw new IllegalArgumentException(msg) with NoStackTrace
  }
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

@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[ConstantBackoffConfig], name = "constant"),
  new JsonSubTypes.Type(value = classOf[JitteredBackoffConfig], name = "jittered")
))
abstract class BackoffConfig extends PolymorphicConfig {
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