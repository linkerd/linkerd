package io.buoyant.telemetry

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack
import com.twitter.finagle.stats.{DefaultStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{DefaultTracer, NullTracer}
import io.buoyant.linkerd.admin.{DashboardHandler, MetricsHandler}

class DefaultInitializer extends TelemeterInitializer {
  type Config = DefaultConfig
  def configClass = classOf[DefaultConfig]
  override def configId = "io.l5d.default"
}

case class DefaultConfig(
  stats: Option[Boolean],
  tracing: Option[Boolean]
) extends TelemeterConfig {

  @JsonIgnore def mk(params: Stack.Params): DefaultTelemeter =
    new DefaultTelemeter(stats.getOrElse(true), tracing.getOrElse(true))
}

class DefaultTelemeter(doStats: Boolean, doTracing: Boolean) extends Telemeter {

  val stats = if (doStats) DefaultStatsReceiver else NullStatsReceiver

  val tracer = if (doTracing) DefaultTracer else NullTracer

  val handlers = Map(
    "/metrics" -> MetricsHandler,
    "/" -> new DashboardHandler
  )

  def run() = Telemeter.nopRun
}
