package io.buoyant.linkerd

import com.twitter.util.Duration
import sun.misc.{Signal, SignalHandler}

trait TerminationHook {

  /**
   * Trap termination signals and triggers an App.close for a graceful shutdown.
   * Shutdown hook is not used because it has, at least, the following problems:
   * <ul>
   *   <li>LogManager uses a shutdown hook which makes nothing to be logged during shutdown
   *   <li>TracerCache uses a shutdown hook to flush
   * </ul>
   */
  def register(shutdown: Duration => Unit, shutdownGrace: Duration): Unit = {
    val shutdownHandler = new SignalHandler {
      override def handle(sig: Signal): Unit =
        shutdown(shutdownGrace)
    }

    Signal.handle(new Signal("INT"), shutdownHandler)
    val _ = Signal.handle(new Signal("TERM"), shutdownHandler)
  }
}

object TerminationHook extends TerminationHook
