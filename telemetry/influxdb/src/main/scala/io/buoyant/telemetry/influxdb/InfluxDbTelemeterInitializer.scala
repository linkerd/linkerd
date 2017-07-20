package io.buoyant.telemetry.influxdb

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack
import io.buoyant.telemetry.{MetricsTree, Telemeter, TelemeterConfig, TelemeterInitializer}

class InfluxDbTelemeterInitializer extends TelemeterInitializer {
  type Config = InfluxDbConfig
  val configClass = classOf[InfluxDbConfig]
  override val configId = "io.l5d.influxdb"
}

object InfluxDbTelemeterInitializer extends InfluxDbTelemeterInitializer

class InfluxDbConfig extends TelemeterConfig {
  @JsonIgnore def mk(params: Stack.Params): Telemeter = new InfluxDbTelemeter(params[MetricsTree])
}
