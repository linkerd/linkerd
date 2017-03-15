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
    if (labels.nonEmpty) {
      labels.map {
        case (k, v) =>
          s"""$k="$v""""
      }.mkString("{", ", ", "}")
    } else {
      ""
    }

  private[this] def labelExists(labels: Seq[(String, String)], name: String) =
    labels.exists(_._1 == name)

  private[this] def writeMetrics(
    tree: MetricsTree,
    sb: StringBuilder,
    prefix0: Seq[String] = Nil,
    labels0: Seq[(String, String)] = Nil
  ): Unit = {

    // Re-write elements out of the prefix into labels
    val (prefix1, labels1) = prefix0 match {
      case Seq("rt", router) if !labelExists(labels0, "rt") =>
        (Seq("rt"), labels0 :+ ("rt" -> router))
      case Seq("rt", "dst", "path", path) if !labelExists(labels0, "dst_path") =>
        (Seq("rt", "dst_path"), labels0 :+ ("dst_path" -> path))
      case Seq("rt", "dst", "id", id) if !labelExists(labels0, "dst_id") =>
        (Seq("rt", "dst_id"), labels0 :+ ("dst_id" -> id))
      case Seq("rt", "dst_id", "path", path) if !labelExists(labels0, "dst_path") =>
        (Seq("rt", "dst_id", "dst_path"), labels0 :+ ("dst_path" -> path))
      case Seq("rt", "srv", srv) if !labelExists(labels0, "srv") =>
        (Seq("rt", "srv"), labels0 :+ ("srv" -> srv))
      case _ => (prefix0, labels0)
    }

    val key = escapeKey(prefix1.mkString(":"))

    tree.metric match {
      case c: Metric.Counter =>
        sb.append(key)
        sb.append(formatLabels(labels1))
        sb.append(" ")
        sb.append(c.get)
        sb.append("\n")
      case g: Metric.Gauge =>
        sb.append(key)
        sb.append(formatLabels(labels1))
        sb.append(" ")
        sb.append(g.get)
        sb.append("\n")
      case s: Metric.Stat =>
        val summary = s.snapshottedSummary
        if (summary != null) {
          for (
            (stat, value) <- Seq(
              "count" -> summary.count, "sum" -> summary.sum, "avg" -> summary.avg
            )
          ) {
            sb.append(key)
            sb.append("_")
            sb.append(stat)
            sb.append(formatLabels(labels1))
            sb.append(" ")
            sb.append(value)
            sb.append("\n")
          }
          for (
            (percentile, value) <- Seq(
              "0" -> summary.min, "0.5" -> summary.p50,
              "0.9" -> summary.p90, "0.95" -> summary.p95, "0.99" -> summary.p99,
              "0.999" -> summary.p9990, "0.9999" -> summary.p9999,
              "1" -> summary.max
            )
          ) {
            sb.append(key)
            sb.append(formatLabels(labels1 :+ ("quantile" -> percentile)))
            sb.append(" ")
            sb.append(value)
            sb.append("\n")
          }
        }
      case _ =>
    }

    for ((name, child) <- tree.children) {
      writeMetrics(child, sb, prefix1 :+ name, labels1)
    }
  }
}
