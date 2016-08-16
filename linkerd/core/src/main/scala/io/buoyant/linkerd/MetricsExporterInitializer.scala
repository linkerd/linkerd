package io.buoyant.linkerd

import com.twitter.common.metrics.MetricProvider
import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.config.ConfigInitializer

trait MetricsExporterInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait MetricsExporterConfig {

  @JsonIgnore
  def mk(metrics: MetricProvider, timer: Timer = DefaultTimer.twitter): MetricsExporter
}

class PeriodMetricsExporter(
  metrics: MetricProvider,
  export: MetricProvider => Unit,
  period: Duration,
  timer: Timer
) extends MetricsExporter {

  private[this] val task = timer.schedule(period)(export(metrics))

  override def close(deadline: Time): Future[Unit] = task.close(deadline)
}

trait MetricsExporter extends Closable
