package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.liveness.FailureAccrualPolicy
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}

class SuccessRateInitializer extends FailureAccrualInitializer {
  val configClass = classOf[SuccessRateConfig]
  override def configId = "io.l5d.successRate"
}

object SuccessRateInitializer extends SuccessRateInitializer

case class SuccessRateConfig(successRate: Double, requests: Int) extends FailureAccrualConfig {
  @JsonIgnore
  override def policy =
    () => FailureAccrualPolicy.successRate(successRate, requests, backoffOrDefault)
}
