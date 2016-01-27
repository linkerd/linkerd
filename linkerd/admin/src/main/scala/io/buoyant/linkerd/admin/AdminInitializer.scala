package io.buoyant.linkerd.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.{Level, Logger}
import com.twitter.server.view.NotFoundView
import com.twitter.util.Monitor
import io.buoyant.linkerd.Admin
import io.buoyant.linkerd.Admin.AdminPort
import java.net.InetSocketAddress

class AdminInitializer(admin: Admin, svc: Service[Request, Response]) {

  private[this] val log = Logger()

  @volatile protected var _adminHttpServer: ListeningServer = NullServer
  def adminHttpServer: ListeningServer = _adminHttpServer

  def startServer(): Unit = {
    val loggingMonitor = new Monitor {
      def handle(exc: Throwable): Boolean = {
        log.log(Level.ERROR, s"Caught exception in AdminInitializer: $exc", exc)
        false
      }
    }
    val AdminPort(port) = admin.params[AdminPort]
    log.info(s"Serving admin http on $port")
    _adminHttpServer = Http.server
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .configured(param.Monitor(loggingMonitor))
      .configured(param.Label("adminhttp"))
      .serve(new InetSocketAddress(port), new NotFoundView andThen svc)
  }
}
