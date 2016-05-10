package io.buoyant.admin

import com.twitter.common.metrics.Metrics
import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.io.Charsets
import com.twitter.util.Future
import org.jboss.netty.buffer.ChannelBuffers
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.util.matching.Regex

/**
 * PrometheusStatsHandler
 *
 * Export Stats in a Prometheus-compatible format. Prometheus (http://prometheus.io/)
 * exports stats in a somewhat different format than the Finagle's MetricsExporter. Specifically,
 * instead of delimiting scopes by forward slashes, a colon is used. For stats, instead of exporting
 * percentiles (p50, p99, etc.) and other quantities (max, min, etc.) as individual stats, they
 * should be exported as labels in the Prometheus format.
 */
private[admin] object PrometheusStatsHandler {
  private[this] case class Escape(regex: Regex, replace: String) {
    private[admin] def replaceAllIn(str: String) = regex.replaceAllIn(str, replace)
  }
  private[this] val escape1 = Escape("[!,]".r, "-")
  private[this] val escape2 = Escape("[/]".r, ":")
  private[this] val statPattern = """(.*)\.(count|sum|avg|min|max|stddev|p50|p90|p95|p99|p9990)$""".r

  private[this] val escape3 = Escape("[.\\-$]".r, "_")

  private[admin] def formatKey(key: String) = {
    val escapedKey = escape2.replaceAllIn(escape1.replaceAllIn(key))

    val parsedKey = escapedKey match {
      case statPattern(label, stat) => s"""$label{stat="$stat"}"""
      case _ => escapedKey
    }

    escape3.replaceAllIn(parsedKey)
  }
}

private[admin] class PrometheusStatsHandler(registry: Metrics) extends Service[Request, Response] {
  import PrometheusStatsHandler._

  override def apply(request: Request): Future[Response] = {
    val rsp = Response()
    rsp.contentType = MediaType.Txt

    val output = new StringBuilder
    registry.sample().asScala.foreach {
      case (key, value) => output ++= s"""${formatKey(key)} $value\n"""
    }
    output ++= "\n"
    rsp.contentString = output.toString
    Future.value(rsp)
  }
}
