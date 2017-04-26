package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.liveness.FailureAccrualPolicy
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}

class ConsecutiveFailuresInitializer extends FailureAccrualInitializer {
  val configClass = classOf[ConsecutiveFailuresConfig]
  override def configId = "io.l5d.consecutiveFailures"
}

object ConsecutiveFailuresInitializer extends ConsecutiveFailuresInitializer

case class ConsecutiveFailuresConfig(failures: Int) extends FailureAccrualConfig {
  @JsonIgnore
  override def policy =
    () => FailureAccrualPolicy.consecutiveFailures(failures, backoffOrDefault)
}
