package io.buoyant.linkerd.failureAccrual

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.liveness.FailureAccrualPolicy
import com.twitter.util.Duration
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}

class NoneInitializer extends FailureAccrualInitializer {
  val configClass = classOf[NoneConfig]
  override def configId = "none"
}

object NoneInitializer extends NoneInitializer

class NoneConfig extends FailureAccrualConfig {
  @JsonIgnore
  override def policy = () => NonePolicy
}

object NonePolicy extends FailureAccrualPolicy {
  override def recordSuccess(): Unit = {}
  override def markDeadOnFailure(): Option[Duration] = None
  override def revived(): Unit = {}

  override def name: String = "NonePolicy"

  override def show(): String = ""
}
