package io.buoyant.admin

import com.twitter.app.{App => TApp}
import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.finagle.stats.{MetricsStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.Admin.Path
import com.twitter.server.handler.{SummaryHandler => _, _}
import com.twitter.server.view.{IndexView, TextBlockView, NotFoundView}
import com.twitter.util.Monitor
import java.net.SocketAddress

object Admin {
  val label = "adminhttp"
  private val log = Logger.get(label)

  type Handler = Service[Request, Response]
  type Handlers = Seq[(String, Handler)]

  /**
   * A type for modules that expose admin handlers.
   */
  trait WithHandlers {
    def adminHandlers: Handlers
  }

  def getHandlers(obj: AnyRef): Handlers =
    obj match {
      case wh: WithHandlers => wh.adminHandlers
      case _ => Nil
    }

  def extractHandlers(objs: Seq[AnyRef]): Handlers =
    objs.flatMap(getHandlers(_))

  private val loggingMonitor = new Monitor {
    def handle(exc: Throwable): Boolean = {
      log.error(exc, label)
      false
    }
  }

  private val server = Http.server
    .withLabel(label)
    .withMonitor(loggingMonitor)
    .withStatsReceiver(NullStatsReceiver)
    .withTracer(NullTracer)

  def appHandlers(app: TApp): Handlers = Seq(
    "/admin/server_info" -> new TextBlockView().andThen(new ServerInfoHandler(app)),
    "/admin/shutdown" -> new ShutdownHandler(app)
  )

  def baseHandlers: Handlers = Seq(
    "/admin/contention" -> (new TextBlockView andThen new ContentionHandler),
    "/admin/lint" -> new LintHandler,
    "/admin/lint.json" -> new LintHandler,
    "/admin/threads" -> new ThreadsHandler,
    "/admin/threads.json" -> new ThreadsHandler,
    "/admin/announcer" -> (new TextBlockView andThen new AnnouncerHandler),
    "/admin/pprof/heap" -> new HeapResourceHandler,
    "/admin/pprof/profile" -> new ProfileResourceHandler(Thread.State.RUNNABLE),
    "/admin/pprof/contention" -> new ProfileResourceHandler(Thread.State.BLOCKED),
    "/admin/ping" -> new ReplyHandler("pong"),
    "/admin/tracing" -> new TracingHandler,
    "/admin/logging" -> (new StyleOverrideFilter andThen new LoggingHandler),
    "/admin/registry.json" -> new RegistryHandler,
    "/favicon.png" -> ResourceHandler.fromJar(
      baseRequestPath = "/",
      baseResourcePath = "io/buoyant/linkerd/admin/images"
    )
  )

  // XXX this will be moved into telemeters soon
  private def metricsHandlers: Seq[(String, Service[Request, Response])] = Seq(
    "/admin/metrics" -> new MetricsQueryHandler,
    "/admin/metrics/prometheus" -> new PrometheusStatsHandler(MetricsStatsReceiver.defaultRegistry),
    "/admin/metrics.json" -> HttpMuxer,
    "/admin/per_host_metrics.json" -> HttpMuxer
  )

  /** Generate an index of the provided handlers */
  private def indexHandlers(handlers: Handlers): Handlers = {
    val paths = handlers.map { case (p, _) => p }.sorted.distinct
    val index = new IndexTxtHandler(paths)
    Seq(
      "/admin/index.txt" -> index,
      "/admin" -> index
    )
  }
}

class Admin(val address: SocketAddress) {
  import Admin._

  private[this] val notFoundView = new NotFoundView()

  def mkService(app: TApp, extHandlers: Admin.Handlers): Service[Request, Response] = {
    val handlers = baseHandlers ++ appHandlers(app) ++ metricsHandlers ++ extHandlers
    val muxer = (handlers ++ indexHandlers(handlers)).foldLeft(new HttpMuxer) {
      case (muxer, (path, handler)) =>
        log.debug(s"admin: $path => ${handler.getClass.getName}")
        muxer.withHandler(path, handler)
    }
    notFoundView.andThen(muxer)
  }

  def serve(app: TApp, extHandlers: Admin.Handlers): ListeningServer =
    server.serve(address, mkService(app, extHandlers))

}
