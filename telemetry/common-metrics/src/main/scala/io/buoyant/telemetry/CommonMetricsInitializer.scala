package io.buoyant.telemetry

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.common.metrics.Metrics
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, SimpleFilter, Stack, http}
import com.twitter.finagle.stats.MetricsStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.server.handler.MetricQueryHandler
import io.buoyant.admin.Admin
import io.buoyant.admin.Admin.Handler
import io.buoyant.telemetry.commonMetrics._

class CommonMetricsInitializer extends TelemeterInitializer {
  type Config = CommonMetricsConfig
  def configClass = classOf[CommonMetricsConfig]
  override def configId = "io.l5d.commonMetrics"
}

case class CommonMetricsConfig()
  extends TelemeterConfig {

  @JsonIgnore def mk(params: Stack.Params): CommonMetricsTelemeter =
    new CommonMetricsTelemeter
}

class CommonMetricsTelemeter(registry: Metrics = Metrics.root)
  extends Telemeter
  with Admin.WithHandlers {
  import CommonMetricsTelemeter._

  val stats = new MetricsStatsReceiver(registry)

  // XXX We should really be passing the registry through to handlers,
  // but this is tricky for the moment.
  val adminHandlers = Seq(
    Handler("/admin/metrics", postToGetFilter.andThen(new MetricQueryHandler)),
    Handler("/admin/metrics.json", http.HttpMuxer),
    Handler("/admin/metrics/prometheus", new PrometheusStatsHandler(registry))
  )

  def tracer = NullTracer
  def run() = Telemeter.nopRun
}

object CommonMetricsTelemeter {

  private val postToGetFilter = new SimpleFilter[http.Request, http.Response] {
    def apply(req: http.Request, service: Service[Request, Response]) = {
      if (req.method == http.Method.Post) {
        req.method = http.Method.Get
        req.uri = s"${req.path}?${req.contentString}"
      }
      service(req)
    }
  }
}
