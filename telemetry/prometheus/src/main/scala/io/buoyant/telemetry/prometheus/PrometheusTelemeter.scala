package io.buoyant.telemetry.prometheus

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.stats.buoyant.Metric
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Awaitable, Closable, Future}
import io.buoyant.admin.Admin
import io.buoyant.telemetry.{MetricsTree, Telemeter}

/**
 * This telemeter exposes metrics data in the Prometheus format, served on
 * an admin endpoint.  It does not provide a StatsReceiver or Tracer.  It reads
 * histogram summaries directly off of the MetricsTree and assumes that stats
 * are being snapshotted at some appropriate interval.
 */
class PrometheusTelemeter(metrics: MetricsTree, private[prometheus] val handlerPath: String, private[prometheus] val handlerPrefix: String) extends Telemeter with Admin.WithHandlers {

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
    Admin.Handler(handlerPath, handler)
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

  private[this] def formatLabels(labels: Seq[(String, String)], sb: StringBuilder): Unit =
    if (labels.nonEmpty) {
      val lastIndex = labels.size - 1
      sb.append("{")
      for (((k, v), i) <- labels.zipWithIndex) {
        sb.append(escapeLabelKey(k))
        sb.append("=\"")
        sb.append(v)
        sb.append("\"")
        if (i < lastIndex) sb.append(", ")
      }
      sb.append("}")
      ()
    }

  private[this] val first: ((String, String)) => String = _._1
  private[this] def labelExists(labels: Seq[(String, String)], name: String) =
    labels.toIterator.map(first).contains(name)

  private[this] def addException(labels: Seq[(String, String)], name: String) = {
    val index = labels.map(first).indexOf("exception")
    if (index == -1) {
      labels :+ ("exception" -> escapeLabelVal(name))
    } else {
      val nested = labels(index)._2
      labels.updated(index, "exception" -> (nested ++ ":" ++ escapeLabelVal(name)))
    }
  }

  private[this] def writeMetrics(
    tree: MetricsTree,
    sb: StringBuilder,
    prefix0: Seq[String] = Nil,
    labels0: Seq[(String, String)] = Nil
  ): Unit = {

    // Re-write elements out of the prefix into labels
    val (prefix1, labels1) = prefix0 match {
      case Seq("rt", router) if !labelExists(labels0, "rt") =>
        (Seq("rt"), labels0 :+ ("rt" -> escapeLabelVal(router)))

      // Add label for stack { "service", "client", "server" }
      case Seq("rt", stack, identifier) if !labelExists(labels0, stack) =>
        (Seq("rt", stack), labels0 :+ (stack -> escapeLabelVal(identifier)))

      // Handle client service case
      case Seq("rt", "client", "service", path) if !labelExists(labels0, "service") =>
        (Seq("rt", "client", "service"), labels0 :+ ("service" -> escapeLabelVal(path)))

      // Add label for exception { "failures", "exn" }
      case Seq("rt", stack, "failures", exception) =>
        (Seq("rt", stack, "failures"), addException(labels0, exception))
      case Seq("rt", stack, "exn", exception) =>
        (Seq("rt", stack, "exceptions"), addException(labels0, exception))

      case _ => (prefix0, labels0)
    }

    val key = escapeKey(handlerPrefix + prefix1.mkString(":"))

    tree.metric match {
      case c: Metric.Counter =>
        sb.append(key)
        formatLabels(labels1, sb)
        sb.append(" ")
        sb.append(c.get)
        sb.append("\n")
      case g: Metric.Gauge =>
        sb.append(key)
        formatLabels(labels1, sb)
        sb.append(" ")
        sb.append(g.get)
        sb.append("\n")
      case s: Metric.Stat =>
        val summary = s.snapshottedSummary
        if (summary != null && summary.count > 0) {
          for (
            (stat, value) <- Seq(
              "count" -> summary.count, "sum" -> summary.sum, "avg" -> summary.avg
            )
          ) {
            sb.append(key)
            sb.append("_")
            sb.append(stat)
            formatLabels(labels1, sb)
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
            formatLabels(labels1 :+ ("quantile" -> escapeLabelVal(percentile)), sb)
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
