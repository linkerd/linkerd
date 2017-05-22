package io.buoyant.telemetry

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Record, Trace, TraceId, Tracer}
import com.twitter.logging.{Level, Logger}

class TracelogInitializer extends TelemeterInitializer {
  type Config = TracelogConfig
  val configClass = classOf[TracelogConfig]
  override val configId = "io.l5d.tracelog"
}

case class TracelogConfig(
  @JsonDeserialize(contentAs = classOf[java.lang.Double]) sampleRate: Option[Double],
  level: Option[Level]
) extends TelemeterConfig {

  private[this] val sampleRateD: Double =
    sampleRate.getOrElse(1.0).min(1.0).max(0.0)

  private[this] val logger = Logger.get("io.l5d.tracelog")
  def mk(params: Stack.Params): TracelogTelemeter =
    new TracelogTelemeter(logger, level.getOrElse(Level.INFO), Sampler(sampleRateD.toFloat))
}

class TracelogTelemeter(logger: Logger, level: Level, sampler: Sampler) extends Telemeter {
  val stats = NullStatsReceiver
  lazy val tracer = new TracelogTracer(logger, level, sampler)
  def run() = Telemeter.nopRun
}

class TracelogTracer(logger: Logger, level: Level, sample: Sampler) extends Tracer {

  // This tracer doesn't influence downstream tracing.
  def sampleTrace(id: TraceId): Option[Boolean] = None

  def record(record: Record): Unit = {
    val id = record.traceId
    if (id.sampled.getOrElse(sample(id.traceId.toLong))) {
      logger.log(level, "%s", record)
    }
  }

}
