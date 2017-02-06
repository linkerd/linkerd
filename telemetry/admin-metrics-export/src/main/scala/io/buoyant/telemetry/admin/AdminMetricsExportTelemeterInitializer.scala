package io.buoyant.telemetry.admin

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.util.DefaultTimer
import io.buoyant.telemetry.{MetricsTree, Telemeter, TelemeterConfig, TelemeterInitializer}

case class AdminMetricsExportTelemeterConfig(
  snapshotIntervalSecs: Option[Int] = None
) extends TelemeterConfig {
  @JsonIgnore
  override def mk(params: Params): Telemeter = new AdminMetricsExportTelemeter(
    params[MetricsTree],
    snapshotIntervalSecs.map(_.seconds).getOrElse(1.minute),
    DefaultTimer.twitter
  )
}

class AdminMetricsExportTelemeterInitializer extends TelemeterInitializer {
  type Config = AdminMetricsExportTelemeterConfig
  val configClass = classOf[AdminMetricsExportTelemeterConfig]
  override val configId = "io.l5d.adminMetricsExport"
}
