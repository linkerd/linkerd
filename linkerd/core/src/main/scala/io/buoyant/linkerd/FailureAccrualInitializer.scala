package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.liveness.{FailureAccrualFactory, FailureAccrualPolicy}
import com.twitter.finagle.service.Backoff
import com.twitter.util.Duration
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}

abstract class FailureAccrualInitializer extends ConfigInitializer

abstract class FailureAccrualConfig extends PolymorphicConfig {
  @JsonIgnore
  def policy: () => FailureAccrualPolicy

  var backoff: Option[BackoffConfig] = None

  @JsonIgnore
  def backoffOrDefault: Stream[Duration] =
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

  def default: FailureAccrualFactory.Param = FailureAccrualFactory.Param(defaultPolicy)

  def param(config: FailureAccrualConfig): FailureAccrualFactory.Param =
    FailureAccrualFactory.Param(config.policy)
}
