package io.buoyant.telemetry.requestLog

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import io.buoyant.telemetry.{Telemeter, TelemeterConfig, TelemeterInitializer}

class RequestLogInitializer extends TelemeterInitializer {
  type Config = RequestLogConfig
  override val configId = "io.l5d.requestLog"
  val configClass = classOf[RequestLogConfig]
}

case class RequestLogConfig(sampleRate: Option[Double], capacity: Option[Int] = None) extends TelemeterConfig {
  @JsonIgnore
  private[this] val _sampleRate = sampleRate
    .filter(r => r >= 0.0 && r <= 1.0)
    .getOrElse(throw new IllegalArgumentException("sampleRate must be between 0.0 and 1.0"))

  @JsonIgnore
  override def mk(params: Params): Telemeter =
    new RequestLogTelemeter(_sampleRate, capacity.getOrElse(10))
}
