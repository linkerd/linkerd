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
  val runNop: Closable with Awaitable[Unit] =
    new Closable with CloseAwaitably {
      def close(d: Time) = closeAwaitably(Future.Unit)
    }
}
