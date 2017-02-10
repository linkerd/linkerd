package io.buoyant.telemetry.prometheus

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Awaitable, Closable, Future}
import io.buoyant.admin.Admin
import io.buoyant.telemetry.{Metric, MetricsTree, Telemeter}

/**
 * This telemeter exposes metrics data in the Prometheus format, served on
 * an admin endpoint.  It does not provide a StatsReceiver or Tracer.  It reads
 * histogram summaries directly off of the MetricsTree and assumes that stats
 * are being snapshotted at some appropriate interval.
 */
class PrometheusTelemeter(metrics: MetricsTree) extends Telemeter with Admin.WithHandlers {

  private[prometheus] val handler = Service.mk { request: Request =>
    val response = request.response
    response.mediaType = MediaType.Txt
    val sb = new StringBuilder()
    writeMetrics(metrics, sb)
    response.contentString = sb.toString
    Future.value(response)
  }

  val adminHandlers: Seq[Admin.Handler] = Seq(
    Admin.Handler("/admin/metrics/prometheus", handler)
  )

  val stats = NullStatsReceiver
  def tracer = NullTracer
  def run(): Closable with Awaitable[Unit] = Telemeter.nopRun

  private[this] val disallowedChars = "[^a-zA-Z0-9:]".r
  private[this] def escapeKey(key: String) = disallowedChars.replaceAllIn(key, "_")

  private[this] def formatLabels(labels: Seq[(String, String)]): String =
    labels.map {
      case (k, v) =>
        s"""$k="$v""""
    }.mkString("{", ", ", "}")

  private[this] val CounterLabel = "type" -> "counter"
  private[this] val GaugeLabel = "type" -> "gauge"
  private[this] val StatLabel = "type" -> "stat"

  private[this] def writeMetrics(
    tree: MetricsTree,
    sb: StringBuilder,
    prefix0: Seq[String] = Nil,
    labels0: Seq[(String, String)] = Nil
  ): Unit = {

    // Re-write elements out of the prefix into labels
    val (prefix1, labels1) = prefix0 match {
      case Seq("rt", router) => (Nil, labels0 :+ ("rt" -> router))
      case Seq("dst", "path", path) => (Nil, labels0 :+ ("dst_path" -> path))
      case Seq("dst", "id", id) => (Nil, labels0 :+ ("dst_id" -> id))
      case Seq("path", path) => (Nil, labels0 :+ ("dst_path" -> path))
      case _ => (prefix0, labels0)
    }

    val key = escapeKey(prefix1.mkString(":"))

    tree.metric match {
      case c: Metric.Counter =>
        sb.append(key)
        sb.append(formatLabels(labels1 :+ CounterLabel))
        sb.append(" ")
        sb.append(c.get)
        sb.append("\n")
      case g: Metric.Gauge =>
        sb.append(key)
        sb.append(formatLabels(labels1 :+ GaugeLabel))
        sb.append(" ")
        sb.append(g.get)
        sb.append("\n")
      case s: Metric.Stat =>
        val summary = s.summary
        for (
          (stat, value) <- Seq(
            "count" -> summary.count, "min" -> summary.min, "max" -> summary.max, "sum" -> summary.sum,
            "p50" -> summary.p50, "p90" -> summary.p90, "p99" -> summary.p99, "p9990" -> summary.p9990,
            "p9999" -> summary.p9999, "avg" -> summary.avg
          )
        ) {
          sb.append(key)
          sb.append(formatLabels(labels1 :+ StatLabel :+ ("stat" -> stat)))
          sb.append(" ")
          sb.append(value)
          sb.append("\n")
        }
      case _ =>
    }

    for ((name, child) <- tree.children) {
      writeMetrics(child, sb, prefix1 :+ name, labels1)
    }
  }
}
