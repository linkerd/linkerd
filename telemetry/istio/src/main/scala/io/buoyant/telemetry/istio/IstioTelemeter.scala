package io.buoyant.telemetry.istio

import com.twitter.conversions.time._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.util.{Awaitable, Closable, CloseAwaitably, Time, Timer}
import io.buoyant.telemetry.Telemeter
import java.util.concurrent.atomic.AtomicBoolean

private[telemetry] object IstioTelemeter {
  private[telemetry] val log = Logger.get(getClass.getName)
}

private[telemetry] class IstioTelemeter(
  client: MixerClient,
  timer: Timer
) extends Telemeter {
  import IstioTelemeter._

  val stats = NullStatsReceiver
  val tracer = NullTracer

  private[this] val started = new AtomicBoolean(false)

  // only run at most once
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    // TODO: for now just flush a fake telemetry message every 2000ms
    val task = timer.schedule(2000.millis) {
      val _ = client()
    }

    val closer = Closable.all(task)

    new Closable with CloseAwaitably {
      def close(deadline: Time) = closeAwaitably {
        closer.close(deadline)
      }
    }
  }
}
