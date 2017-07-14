package io.buoyant.telemetry

import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Tracer, NullTracer}
import com.twitter.util.{Awaitable, Closable, CloseAwaitably, Future, Time}

/**
 * A telemeter may receive stats and trace annotations, i.e. to send
 * to a collector or export.
 */
trait Telemeter {
  def stats: StatsReceiver
  def tracer: Tracer
  def run(): Closable with Awaitable[Unit]
}

object Telemeter {

  // default initial buffer size for StringBuilder objects, used by
  // InfluxDbTelemeter and PrometheusTelemeter
  val DefaultBufferSize = 16384

  /**
   * A utility useful when a Telemeter has no _run_ning to do.
   */
  val nopRun: Closable with Awaitable[Unit] =
    new Closable with CloseAwaitably {
      def close(t: Time) = closeAwaitably(Future.Unit)
    }
}

object NullTelemeter extends Telemeter {
  def stats = NullStatsReceiver
  def tracer = NullTracer
  def run() = Telemeter.nopRun
}
