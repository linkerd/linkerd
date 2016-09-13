package io.buoyant.telemetry

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.common.metrics.Metrics
import com.twitter.finagle.{Service, SimpleFilter, Stack, http}
import com.twitter.finagle.stats.{MetricsExporter, MetricsStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{DefaultTracer, NullTracer}
import com.twitter.server.handler.MetricQueryHandler
import io.buoyant.admin.Admin

class TwitterMetricsInitializer extends TelemeterInitializer {
  type Config = TwitterMetricsConfig
  def configClass = classOf[TwitterMetricsConfig]
  override def configId = "io.l5d.twittermetrics"
}

class TwitterMetricsConfig extends TelemeterConfig {
  @JsonIgnore def mk(params: Stack.Params): TwitterMetricsTelemeter =
    new TwitterMetricsTelemeter()
}

class TwitterMetricsTelemeter(
  registry: Metrics = MetricsStatsReceiver.defaultRegistry
) extends Telemeter {

  val stats = new MetricsStatsReceiver(registry)
  val tracer = NullTracer

  private[this] val postToQuery = new SimpleFilter[http.Request, http.Response] {
    def apply(req: http.Request, service: Service[http.Request, http.Response]) = {
      if (req.method == http.Method.Post) {
        req.uri = s"/admin/metrics?${req.contentString}"
      }
      service(req)
    }
  }

  val adminRoutes: Admin.Routes = Seq(
    "/admin/metrics" -> postToQuery.andThen(new MetricQueryHandler),
    "/admin/metrics.json" -> new MetricsExporter(registry),
    // XXX rename this? or configurable?
    "/admin/metrics/prometheus" -> new PrometheusStatsHandler(registry)
  )

  def run() = Telemeter.nopRun
}
