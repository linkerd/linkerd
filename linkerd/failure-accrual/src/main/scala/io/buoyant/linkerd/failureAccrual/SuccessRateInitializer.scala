package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.service.exp.FailureAccrualPolicy
import io.buoyant.linkerd.{BackoffConfig, FailureAccrualConfig, FailureAccrualInitializer}

class SuccessRateInitializer extends FailureAccrualInitializer {
  val configClass = classOf[SuccessRateConfig]
  override def configId = "io.l5d.successRate"
}

object SuccessRateInitializer extends SuccessRateInitializer

case class SuccessRateConfig(
  sr: Double,
  requests: Int,
  backoff: Option[BackoffConfig]
) extends FailureAccrualConfig {
  @JsonIgnore
  override def policy =
    () => FailureAccrualPolicy.successRate(sr, requests, backoffOrDefault)
}
