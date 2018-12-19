package io.buoyant.telemetry.statsd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.{Awaitable, Closable, CloseAwaitably, Future, Time, Timer}
import io.buoyant.telemetry.Telemeter
import java.util.concurrent.atomic.AtomicBoolean

private[telemetry] class StatsDTelemeter(
  val stats: StatsDStatsReceiver,
  gaugeIntervalMs: Int,
  timer: Timer
) extends Telemeter {

  // no tracer with statsd
  val tracer = NullTracer

  private[this] val started = new AtomicBoolean(false)

  // only run at most once
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    val task = timer.schedule(gaugeIntervalMs.millis) {
      stats.flush
    }

    val closer = Closable.all(
      task,
      Closable.make(_ => Future.value(stats.close()))
    )

    new Closable with CloseAwaitably {
      def close(deadline: Time) = closeAwaitably {
        closer.close(deadline)
      }
    }
  }
}
