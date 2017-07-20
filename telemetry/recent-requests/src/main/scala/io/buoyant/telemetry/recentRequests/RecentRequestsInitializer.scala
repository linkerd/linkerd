package io.buoyant.telemetry.recentRequests

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.finagle.Stack.Params
import io.buoyant.telemetry.{Telemeter, TelemeterConfig, TelemeterInitializer}

class RecentRequestsInitializer extends TelemeterInitializer {
  type Config = RecentRequestsConfig
  override val configId = "io.l5d.recentRequests"
  val configClass = classOf[RecentRequestsConfig]
}

case class RecentRequestsConfig(
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) sampleRate: Option[Double],
  capacity: Option[Int] = None
) extends TelemeterConfig {
  @JsonIgnore
  private[this] val _sampleRate = sampleRate
    .filter(r => r >= 0.0 && r <= 1.0)
    .getOrElse(throw new IllegalArgumentException("sampleRate must be between 0.0 and 1.0"))

  @JsonIgnore
  override def mk(params: Params): Telemeter =
    new RecentRequestsTelemeter(_sampleRate, capacity.getOrElse(10))
}
