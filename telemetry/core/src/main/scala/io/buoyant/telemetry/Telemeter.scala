package io.buoyant.telemetry

import com.twitter.finagle.{Service, http}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Tracer
import com.twitter.util.{Awaitable, Closable, CloseAwaitably, Future, Time}
import io.buoyant.admin.Admin

/**
 * A telemeter may receive stats and trace annotations, i.e. to send
 * to a collector or export.
 */
trait Telemeter {
  def stats: StatsReceiver
  def tracer: Tracer
  def adminRoutes: Admin.Routes
  def run(): Closable with Awaitable[Unit]
}

object Telemeter {

  /**
   * A utility useful when a Telemeter has no _run_ning to do.
   */
  val nopRun: Closable with Awaitable[Unit] =
    new Closable with CloseAwaitably {
      def close(t: Time) = closeAwaitably(Future.Unit)
    }
}
