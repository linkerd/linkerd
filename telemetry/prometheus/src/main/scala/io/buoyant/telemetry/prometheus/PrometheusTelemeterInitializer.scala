package io.buoyant.telemetry.prometheus

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack
import io.buoyant.telemetry.{MetricsTree, Telemeter, TelemeterConfig, TelemeterInitializer}

class PrometheusTelemeterInitializer extends TelemeterInitializer {
  type Config = PrometheusConfig
  val configClass = classOf[PrometheusConfig]
  override val configId = "io.l5d.prometheus"
}

object PrometheusTelemeterInitializer extends PrometheusTelemeterInitializer

class PrometheusConfig extends TelemeterConfig {
  @JsonIgnore def mk(params: Stack.Params): Telemeter = new PrometheusTelemeter(params[MetricsTree])
}
