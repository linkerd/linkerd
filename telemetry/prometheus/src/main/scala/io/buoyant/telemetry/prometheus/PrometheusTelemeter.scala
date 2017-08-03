package io.buoyant.telemetry.prometheus

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Response, Request}
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
    val response = Response()
    response.version = request.version
    response.mediaType = MediaType.Txt
    val sb = new StringBuilder(Telemeter.DefaultBufferSize)
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

  private[this] val metricNameDisallowedChars = "[^a-zA-Z0-9:]".r
  private[this] def escapeKey(key: String) = metricNameDisallowedChars.replaceAllIn(key, "_")

  private[this] val labelKeyDisallowedChars = "[^a-zA-Z0-9_]".r
  private[this] def escapeLabelKey(key: String) = labelKeyDisallowedChars.replaceAllIn(key, "_")

  // https://prometheus.io/docs/instrumenting/exposition_formats/#text-format-details
  private[this] val labelValDisallowedChars = """(\\|\"|\n)""".r
  private[this] def escapeLabelVal(key: String) = labelValDisallowedChars.replaceAllIn(key, """\\\\""")

  private[this] def formatLabels(labels: Seq[(String, String)]): String =
    if (labels.nonEmpty) {
      labels.map {
        case (k, v) =>
          s"""${escapeLabelKey(k)}="${escapeLabelVal(v)}""""
      }.mkString("{", ", ", "}")
    } else {
      ""
    }

  private[this] val first: ((String, String)) => String = _._1
  private[this] def labelExists(labels: Seq[(String, String)], name: String) =
    labels.toIterator.map(first).contains(name)

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
      case Seq("rt", "service", path) if !labelExists(labels0, "service") =>
        (Seq("rt", "service"), labels0 :+ ("service" -> path))
      case Seq("rt", "client", id) if !labelExists(labels0, "client") =>
        (Seq("rt", "client"), labels0 :+ ("client" -> id))
      case Seq("rt", "client", "service", path) if !labelExists(labels0, "service") =>
        (Seq("rt", "client", "service"), labels0 :+ ("service" -> path))
      case Seq("rt", "server", srv) if !labelExists(labels0, "server") =>
        (Seq("rt", "server"), labels0 :+ ("server" -> srv))
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
