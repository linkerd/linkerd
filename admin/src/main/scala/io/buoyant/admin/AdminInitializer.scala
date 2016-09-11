package io.buoyant.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.view.NotFoundView
import com.twitter.util.{Awaitable, Closable, Monitor}
import java.net.InetSocketAddress

object AdminInitializer {
  private[this] val label = "adminhttp"
  private[this] val log = Logger(label)

  private[this] val loggingMonitor = new Monitor {
    def handle(exc: Throwable): Boolean = {
      log.error(exc, label)
      false
    }
  }

  def run(admin: AdminConfig, svc: Service[Request, Response]): Closable with Awaitable[Unit] =
    Http.server
      .withLabel(label)
      .withMonitor(loggingMonitor)
      .withStatsReceiver(NullStatsReceiver)
      .withTracer(NullTracer)
      .serve(new InetSocketAddress(admin.port.port), new NotFoundView().andThen(svc))
}
