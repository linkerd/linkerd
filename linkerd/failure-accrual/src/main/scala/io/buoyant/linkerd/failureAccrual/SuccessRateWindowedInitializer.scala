package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.liveness.FailureAccrualPolicy
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}

class SuccessRateWindowedInitializer extends FailureAccrualInitializer {
  val configClass = classOf[SuccessRateWindowedConfig]
  override def configId = "io.l5d.successRateWindowed"
}

object SuccessRateWindowedInitializer extends SuccessRateWindowedInitializer

case class SuccessRateWindowedConfig(
  successRate: Double,
  window: Int
) extends FailureAccrualConfig {
  @JsonIgnore
  override def policy =
    () =>
      FailureAccrualPolicy.successRateWithinDuration(successRate, window.seconds, backoffOrDefault)
}
