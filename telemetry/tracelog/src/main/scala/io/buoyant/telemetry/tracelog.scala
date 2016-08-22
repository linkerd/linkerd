package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.{Trace, Record, TraceId, Tracer}
import com.twitter.logging.Logger

class TracelogInitializer extends TelemeterInitializer {
  type Config = TracelogConfig
  val configClass = classOf[TracelogConfig]
  override val configId = "io.l5d.tracelog"
}

case class TracelogConfig(
  sampleRate: Option[Double]
) extends TelemeterConfig {

  private[this] val sampleRateD: Double =
    sampleRate.getOrElse(1.0).min(1.0).max(0.0)

  private[this] val logger = Logger.get("io.l5d.tracelog")
  def mk(params: Stack.Params): TracelogTelemeter =
    new TracelogTelemeter(logger, Sampler(sampleRateD.toFloat))
}

class TracelogTelemeter(logger: Logger, sampler: Sampler) extends Telemeter {
  val stats = NullStatsReceiver
  lazy val tracer = new TracelogTracer(logger, sampler)
  def run() = Telemeter.runNop
}

class TracelogTracer(logger: Logger, sample: Sampler) extends Tracer {

  // This tracer doesn't influence downstream tracing.
  def sampleTrace(id: TraceId): Option[Boolean] = None

  def record(record: Record): Unit = {
    val id = record.traceId
    if (id.sampled.getOrElse(sample(id.traceId.toLong))) {
      logger.info("%s", record)
    }
  }

}
