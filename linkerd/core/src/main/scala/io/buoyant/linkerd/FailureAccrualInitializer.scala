package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import com.twitter.conversions.time._
import com.twitter.finagle.service.{Backoff, FailureAccrualFactory}
import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.util.Duration
import io.buoyant.config.ConfigInitializer

abstract class FailureAccrualInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait FailureAccrualConfig {
  @JsonIgnore
  def policy: () => FailureAccrualPolicy

  val backoff: Option[BackoffConfig]

  @JsonIgnore
  val backoffOrDefault =
    backoff.map(_.mk).getOrElse(FailureAccrualConfig.defaultBackoff)
}

object FailureAccrualConfig {
  // Settings here mirror Finagle's FailureAccrualFactory.defaultPolicy, but are provided
  // in linkerd to make it easier to reason about the default failure accrual settings
  private val defaultConsecutiveFailures = 5

  private val defaultBackoff: Stream[Duration] =
    Backoff.equalJittered(5.seconds, 300.seconds)

  private val defaultPolicy =
    () => FailureAccrualPolicy.consecutiveFailures(defaultConsecutiveFailures, defaultBackoff)

  def param(config: Option[FailureAccrualConfig]): FailureAccrualFactory.Param =
    FailureAccrualFactory.Param(config.map(_.policy).getOrElse(defaultPolicy))
}
