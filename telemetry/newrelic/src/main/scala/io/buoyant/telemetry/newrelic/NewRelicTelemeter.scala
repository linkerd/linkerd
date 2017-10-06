package io.buoyant.telemetry.newrelic

import com.fasterxml.jackson.annotation.{JsonFormat, JsonValue}
import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Method, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util._
import io.buoyant.config.Parser
import io.buoyant.telemetry.Metric.{Counter, Gauge, Stat, None => MetricNone}
import io.buoyant.telemetry.{MetricsTree, Telemeter}
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.logging.Logger

class NewRelicTelemeter(
  metrics: MetricsTree,
  client: Service[Request, Response],
  licenseKey: String,
  host: String,
  timer: Timer
) extends Telemeter {
  import NewRelicTelemeter._

  private[this] lazy val log = Logger.get()

  private[this] val agent = Agent(host, NewRelicTelemeter.Version)
  private[this] val json = Parser.jsonObjectMapper(Nil)

  val stats = NullStatsReceiver
  def tracer = NullTracer

  private[this] val started = new AtomicBoolean(false)
  // only run at most once
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    val task = timer.schedule(NewRelicTelemeter.Interval) {
      val _ = sendMetrics().respond {
        case Return(r) if r.statusCode == 200 =>
          log.trace("successfully published metrics to New Relic")
        case Return(r) =>
          log.warning("New Relic API returned error %s, %s", r.status, r.contentString)
        case Throw(e) =>
          log.warning("failed to send metrics to New Relic: %s", e)
      }
    }

    new Closable with CloseAwaitably {
      override def close(deadline: Time): Future[Unit] = closeAwaitably(task.close(deadline))
    }
  }

  private[this] def sendMetrics(): Future[Response] = {
    val payload = MetricsPayload(agent, Seq(Component(Name, Guid, Interval.inSeconds, mkMetrics())))
    val req = Request(Method.Post, NewRelicUri)
    req.headerMap.add(LicenseKeyHeader, licenseKey)
    req.mediaType = ContentType
    req.accept = ContentType
    req.withOutputStream(json.writeValue(_, payload))
    client(req)
  }

  private[this] def mkMetrics(): Map[String, Metric] =
    for {
      (key, metric) <- MetricsTree.flatten(metrics).toMap
      newRelicMetric <- metric match {
        case counter: Counter => Option(counter.get).map { ScalarIntegerMetric }
        case gauge: Gauge => Option(gauge.get).map { ScalarDecimalMetric }
        case stat: Stat =>
          Option(stat.snapshottedSummary).map { summary =>
            DistributionMetric(summary.sum, summary.count, summary.min, summary.max)
          }
        case MetricNone => None
      }
    } yield s"Component/Linkerd/$key" -> newRelicMetric

}

object NewRelicTelemeter {
  val NewRelicUri = "/platform/v1/metrics"
  val LicenseKeyHeader = "X-License-Key"
  val Version = "1.0.0" // New Relic plugin version (distinct from Linkerd version)
  val Interval = 1.minute
  val Guid = "io.l5d.linkerd"
  val Name = "Linkerd"
  val ContentType = MediaType.Json
}

case class MetricsPayload(agent: Agent, components: Seq[Component])
case class Agent(host: String, version: String)
case class Component(name: String, guid: String, duration: Long, metrics: Map[String, Metric])

sealed trait Metric

@JsonFormat(shape = JsonFormat.Shape.NUMBER)
case class ScalarIntegerMetric(value: Long) extends Metric {
  @JsonValue def integer: Long = value
}

@JsonFormat(shape = JsonFormat.Shape.NUMBER)
case class ScalarDecimalMetric(@JsonValue value: Float) extends Metric {
  @JsonValue def decimal: Float = value
}
case class DistributionMetric(total: Long, count: Long, min: Long, max: Long) extends Metric
