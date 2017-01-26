package io.buoyant.telemetry.admin

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.util.DefaultTimer
import io.buoyant.telemetry.{MetricsTree, Telemeter, TelemeterConfig, TelemeterInitializer}

case class AdminTelemeterConfig(
  snapshotIntervalSecs: Option[Int] = None
) extends TelemeterConfig {
  @JsonIgnore
  override def mk(params: Params): Telemeter = new AdminTelemeter(
    params[MetricsTree],
    snapshotIntervalSecs.map(_.seconds).getOrElse(1.minute),
    DefaultTimer.twitter
  )
}

class AdminTelemeterInitializer extends TelemeterInitializer {
  type Config = AdminTelemeterConfig
  val configClass = classOf[AdminTelemeterConfig]
  override val configId = "io.l5d.admin"
}
