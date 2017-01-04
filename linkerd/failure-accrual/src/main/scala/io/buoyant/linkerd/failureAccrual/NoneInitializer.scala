package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.service.exp.FailureAccrualPolicy
import com.twitter.util.Duration
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}

class NoneInitializer extends FailureAccrualInitializer {
  val configClass = classOf[NullConfig]
  override def configId = "none"
}

object NoneInitializer extends NoneInitializer

case class NullConfig() extends FailureAccrualConfig {
  @JsonIgnore
  override def policy = () => NullPolicy
}

object NullPolicy extends FailureAccrualPolicy {
  override def recordSuccess(): Unit = {}
  override def markDeadOnFailure(): Option[Duration] = None
  override def revived(): Unit = {}
}
