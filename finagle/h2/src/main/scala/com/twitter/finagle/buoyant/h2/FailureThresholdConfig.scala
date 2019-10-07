package com.twitter.finagle.buoyant.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Stack, StackParams}
import com.twitter.finagle.liveness.FailureDetector
import com.twitter.finagle.liveness.FailureDetector.ThresholdConfig
import com.twitter.conversions.DurationOps._

object FailureThresholdConfig {
  val DefaultMinPeriod = 5.seconds
  val DefaultCloseTimeout = 6.seconds
}

case class FailureThresholdConfig(minPeriodMs: Option[Int], closeTimeoutMs: Option[Int]) {
  @JsonIgnore
  def params: Stack.Params = {
    val thresholdConfig = ThresholdConfig(
      minPeriodMs.map(_.milliseconds).getOrElse(FailureThresholdConfig.DefaultMinPeriod),
      closeTimeoutMs.map(_.milliseconds).getOrElse(FailureThresholdConfig.DefaultCloseTimeout)
    )
    StackParams.empty + FailureDetector.Param(thresholdConfig)
  }
}
