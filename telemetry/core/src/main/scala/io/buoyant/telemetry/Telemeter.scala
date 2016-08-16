package io.buoyant.telemetry

import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{Tracer, NullTracer}
import com.twitter.util.{Awaitable, Closable}

/**
 * A telemeter may receive stats and trace annotations, i.e. to send
 * to a collector or export.
 */
trait Telemeter {
  def stats: StatsReceiver = NullStatsReceiver
  def tracer: Tracer = NullTracer
  def run(): Closable with Awaitable[_]
}
