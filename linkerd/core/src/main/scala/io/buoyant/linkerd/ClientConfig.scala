package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes}
import com.twitter.conversions.time._
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.{DstBindingFactory, PathMatcher, TlsClientPrep}
import com.twitter.finagle.buoyant.TlsClientPrep.{TransportSecurity, Trust}
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.service._
import com.twitter.util.Duration
import io.buoyant.config.PolymorphicConfig
import io.buoyant.router.RetryBudgetConfig
import io.buoyant.router.RetryBudgetModule.param
import scala.util.control.NoStackTrace

/**
 * ClientConfig is a trait containing protocol agnostic client configuration
 * options.  This trait can be mixed into a class to allow these options to be
 * set on that class as part of config deserialization.
 */
trait ClientConfig {

  var tls: Option[TlsClientConfig] = None
  var loadBalancer: Option[LoadBalancerConfig] = None
  var hostConnectionPool: Option[HostConnectionPool] = None
  var failFast: Option[Boolean] = None
  var failureAccrual: Option[FailureAccrualConfig] = None
  var requestAttemptTimeoutMs: Option[Int] = None
  var requeueBudget: Option[RetryBudgetConfig] = None

  @JsonIgnore
  def params(vars: Map[String, String]): Stack.Params = Stack.Params.empty
    .maybeWith(tls.map(_.params(vars)))
    .maybeWith(loadBalancer.map(_.clientParams))
    .maybeWith(hostConnectionPool.map(_.param))
    .maybeWith(requestAttemptTimeoutMs.map(timeout => TimeoutFilter.Param(timeout.millis)))
    .maybeWith(failFast.map(FailFastFactory.FailFast(_)))
    .maybeWith(requeueBudget)
    .maybeWith(failureAccrual.map(FailureAccrualConfig.param(_)))
}

case class TlsClientConfig(
  disableValidation: Option[Boolean],
  commonName: Option[String],
  trustCerts: Option[Seq[String]] = None
) {
  def params(vars: Map[String, String]): Stack.Params = this match {
    case TlsClientConfig(Some(true), _, _) =>
      Stack.Params.empty +
        TransportSecurity(TransportSecurity.Secure()) +
        Trust(Trust.UnsafeNotVerified)
    case TlsClientConfig(_, Some(cn), certs) =>
      Stack.Params.empty +
        TransportSecurity(TransportSecurity.Secure()) +
        Trust(Trust.Verified(PathMatcher.substitute(vars, cn), trustCerts.getOrElse(Nil).map(TlsClientPrep.loadCert(_))))
    case TlsClientConfig(Some(false) | None, None, _) =>
      val msg = "tls is configured with validation but `commonName` is not set"
      throw new IllegalArgumentException(msg) with NoStackTrace
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
