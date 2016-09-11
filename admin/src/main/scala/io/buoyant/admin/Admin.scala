package io.buoyant.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.finagle.stats.MetricsStatsReceiver
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.Admin.Path
import com.twitter.server.handler.{SummaryHandler => TSummaryHandler, _}
import com.twitter.server.view.{IndexView, NotFoundView, TextBlockView}
import com.twitter.util.Monitor
import java.net.SocketAddress

trait Admin[ConfigType] {
  def app: com.twitter.app.App
  def address: SocketAddress
  def config: ConfigType

  private[this] val label = "adminhttp"
  private[this] val log = Logger.get(label)

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

  /** Emulates twitter-server */
  protected[this] val baseRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/admin" -> new TSummaryHandler,
    "/admin/server_info" -> (new TextBlockView andThen new ServerInfoHandler(app)),
    "/admin/contention" -> (new TextBlockView andThen new ContentionHandler),
    "/admin/lint" -> new LintHandler(),
    "/admin/lint.json" -> new LintHandler(),
    "/admin/threads" -> new ThreadsHandler,
    "/admin/threads.json" -> new ThreadsHandler,
    "/admin/announcer" -> (new TextBlockView andThen new AnnouncerHandler),
    "/admin/dtab" -> (new TextBlockView andThen new DtabHandler),
    "/admin/pprof/heap" -> new HeapResourceHandler,
    "/admin/pprof/profile" -> new ProfileResourceHandler(Thread.State.RUNNABLE),
    "/admin/pprof/contention" -> new ProfileResourceHandler(Thread.State.BLOCKED),
    "/admin/ping" -> new ReplyHandler("pong"),
    "/admin/shutdown" -> new ShutdownHandler(app),
    "/admin/tracing" -> new TracingHandler,
    "/admin/logging" -> (new StyleOverrideFilter andThen new LoggingHandler),
    Path.Clients -> new ClientRegistryHandler(Path.Clients),
    Path.Servers -> new ServerRegistryHandler(Path.Servers),
    "/admin/files/" -> ResourceHandler.fromJar(
      baseRequestPath = "/admin/files/",
      baseResourcePath = "twitter-server"
    ),
    "/admin/registry.json" -> new RegistryHandler,
    "/favicon.png" -> ResourceHandler.fromJar(
      baseRequestPath = "/",
      baseResourcePath = "io/buoyant/linkerd/admin/images"
    )
  )

  def serve(extRoutes: Seq[(String, Service[Request, Response])]): ListeningServer = {
    val baseMuxer = baseRoutes.foldLeft(new HttpMuxer) {
      case (muxer, (path, handler)) =>
        log.debug(s"admin: $path => ${handler.getClass.getName}")
        val index = new IndexView(path, path, () => Nil)
        muxer.withHandler(path, index.andThen(handler))
    }
    val muxer = extRoutes.foldLeft(baseMuxer) {
      case (muxer, (path, handler)) =>
        log.debug(s"admin: $path => ${handler.getClass.getName}")
        // BYO index view...
        muxer.withHandler(path, handler)
    }
    val route = new NotFoundView().andThen(muxer)
    server.serve(address, route)
  }
}
