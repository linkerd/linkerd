package io.buoyant.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.{Level, Logger}
import com.twitter.server.view.NotFoundView
import com.twitter.util.Monitor
import java.net.InetSocketAddress
import scala.util.Properties

class AdminInitializer(admin: AdminConfig, svc: Service[Request, Response]) {

  private[this] val log = Logger()

  @volatile protected var _adminHttpServer: ListeningServer = NullServer
  def adminHttpServer: ListeningServer = _adminHttpServer

  def startServer(): Unit = {
    val loggingMonitor = new Monitor {
      def handle(exc: Throwable): Boolean = {
        log.log(Level.ERROR, s"Caught exception in AdminInitializer: $exc", exc)
        log.log(Level.ERROR, exc.getStackTrace.mkString("", Properties.lineSeparator,
          Properties.lineSeparator))
        false
      }
    }
    val port = admin.port.port
    log.info(s"Serving admin http on $port")
    _adminHttpServer = Http.server
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .configured(param.Monitor(loggingMonitor))
      .configured(param.Label("adminhttp"))
      .serve(new InetSocketAddress(port), new NotFoundView andThen svc)
  }
}
