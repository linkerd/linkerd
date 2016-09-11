package io.buoyant.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.view.NotFoundView
import com.twitter.util.{Awaitable, Closable, Monitor}
import java.net.InetSocketAddress

private[buoyant] object AdminInitializer {
  private[this] val label = "adminhttp"

  private[this] val log = Logger(label)
  private[this] val loggingMonitor = new Monitor {
    def handle(exc: Throwable): Boolean = {
      log.error(exc, label)
      false
    }
  }

  private[this] val server = Http.server
    .withLabel(label)
    .withMonitor(loggingMonitor)
    .withStatsReceiver(NullStatsReceiver)
    .withTracer(NullTracer)

  def run(config: AdminConfig, service: Service[Request, Response]): ListeningServer = {
    val addr = new InetSocketAddress(config.port.port)
    val svc = new NotFoundView().andThen(service)
    server.serve(addr, svc)
  }
}
