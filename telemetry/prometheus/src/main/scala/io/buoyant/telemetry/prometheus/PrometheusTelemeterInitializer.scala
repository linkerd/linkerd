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

class PrometheusConfig(path: Option[String], prefix: Option[String]) extends TelemeterConfig {
  @JsonIgnore def mk(params: Stack.Params): Telemeter =
    new PrometheusTelemeter(
      params[MetricsTree],
      path.getOrElse("/admin/metrics/prometheus"),
      prefix.getOrElse("")
    )
}
