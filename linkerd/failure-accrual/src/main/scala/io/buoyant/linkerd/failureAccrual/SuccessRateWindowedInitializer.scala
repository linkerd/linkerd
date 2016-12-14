package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.service.exp.FailureAccrualPolicy
import io.buoyant.linkerd.{BackoffConfig, FailureAccrualConfig, FailureAccrualInitializer}

class SuccessRateWindowedInitializer extends FailureAccrualInitializer {
  val configClass = classOf[SuccessRateWindowedConfig]
  override def configId = "io.l5d.successRateWindowed"
}

object SuccessRateWindowedInitializer extends SuccessRateWindowedInitializer

case class SuccessRateWindowedConfig(
  sr: Double,
  window: Int,
  backoff: Option[BackoffConfig]
) extends FailureAccrualConfig {
  @JsonIgnore
  override def policy =
    () => FailureAccrualPolicy.successRateWithinDuration(sr, window.seconds, backoffOrDefault)
}
