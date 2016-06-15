package io.buoyant.linkerd

import com.twitter.common.metrics.MetricProvider
import com.twitter.finagle.stats.MetricsStatsReceiver
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{Timer, Future, Time, Closable}
import scala.collection.JavaConverters.mapAsScalaMapConverter

class MetricsExport(
  exporters: Seq[MetricsExporterConfig],
  metrics: MetricProvider = MetricsStatsReceiver.defaultRegistry,
  timer: Timer = DefaultTimer.twitter
) extends Closable {

  private[this] val tasks = exporters.map { exporter =>
    timer.schedule(exporter.period) {
      val _ = exporter.export(metrics.sample().asScala)
    }
  }

  override def close(deadline: Time): Future[Unit] = {
    Future.collect(tasks.map(_.close(deadline))).unit
  }
}
