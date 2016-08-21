package io.buoyant.telemetry

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Tracer
import com.twitter.util.{Awaitable, Closable}

/**
 * A telemeter may receive stats and trace annotations, i.e. to send
 * to a collector or export.
 */
trait Telemeter {
  def stats: StatsReceiver
  def tracer: Tracer
  def run(): Closable with Awaitable[Unit]
}
