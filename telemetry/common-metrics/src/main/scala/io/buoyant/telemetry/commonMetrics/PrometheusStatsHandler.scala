package io.buoyant.telemetry.commonMetrics

import com.twitter.common.metrics.Metrics
import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.io.Charsets
import com.twitter.util.Future
import io.buoyant.admin.Admin
import org.jboss.netty.buffer.ChannelBuffers
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.util.matching.Regex

/**
 * PrometheusStatsHandler
 *
 * Export Stats in a Prometheus-compatible format. Prometheus
 * (http://prometheus.io/) exports stats in a somewhat different
 * format than the Finagle's MetricsExporter. Specifically, instead of
 * delimiting scopes by forward slashes, a colon is used. For stats,
 * instead of exporting percentiles (p50, p99, etc.) and other
 * quantities (max, min, etc.) as individual stats, they should be
 * exported as labels in the Prometheus format.
 */
private[telemetry] object PrometheusStatsHandler {
  private[this] case class Escape(regex: Regex, replace: String) {
    def replaceAllIn(str: String) = regex.replaceAllIn(str, replace)
  }
  private[this] val delimiter = Escape("[/]".r, ":")
  private[this] val statPattern = """(.*)\.(count|sum|avg|min|max|stddev|p[0-9]+)$""".r
  private[this] val disallowedChars = Escape("[^a-zA-Z0-9:]".r, "_")

  private[this] def escapeKey(key: String) = {
    disallowedChars.replaceAllIn(delimiter.replaceAllIn(key))
  }

  def formatKey(key: String) = key match {
    case statPattern(label, stat) => s"""${escapeKey(label)}{stat="$stat"}"""
    case _ => escapeKey(key)
  }
}

private[telemetry] class PrometheusStatsHandler(registry: Metrics)
  extends Service[Request, Response] {

  import PrometheusStatsHandler._

  def apply(request: Request): Future[Response] = {
    val output = new StringBuilder
    registry.sample().asScala.foreach {
      case (key, value) => output ++= s"""${formatKey(key)} $value\n"""
    }
    output ++= "\n"

    val rsp = Response()
    rsp.contentType = MediaType.Txt
    rsp.contentString = output.toString
    Future.value(rsp)
  }
}
