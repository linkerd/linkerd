package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.service.exp.FailureAccrualPolicy
import io.buoyant.linkerd.{BackoffConfig, FailureAccrualConfig, FailureAccrualInitializer}

class ConsecutiveFailuresInitializer extends FailureAccrualInitializer {
  val configClass = classOf[ConsecutiveFailuresConfig]
  override def configId = "io.l5d.consecutiveFailures"
}

object ConsecutiveFailuresInitializer extends ConsecutiveFailuresInitializer

case class ConsecutiveFailuresConfig(
  failures: Int,
  backoff: Option[BackoffConfig]
) extends FailureAccrualConfig {
  @JsonIgnore
  override def policy =
    () => FailureAccrualPolicy.consecutiveFailures(failures, backoffOrDefault)
}
