package io.buoyant.admin

import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.Admin.Path
import com.twitter.server.handler.{SummaryHandler => TSummaryHandler, _}
import com.twitter.server.view.{IndexView, TextBlockView, NotFoundView}
import com.twitter.util.Monitor
import java.net.SocketAddress

object Admin {
  type Route = Service[Request, Response]
  type Routes = Seq[(String, Route)]

  val label = "adminhttp"
  private val log = Logger.get(label)

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

  def appRoutes(app: com.twitter.app.App): Routes = Seq(
    "/admin/server_info" -> new TextBlockView().andThen(new ServerInfoHandler(app)),
    "/admin/shutdown" -> new ShutdownHandler(app)
  )

  def baseRoutes: Routes = Seq(
    "/admin" -> new TSummaryHandler,
    "/admin/contention" -> (new TextBlockView andThen new ContentionHandler),
    "/admin/lint" -> new LintHandler(),
    "/admin/lint.json" -> new LintHandler(),
    "/admin/threads" -> new ThreadsHandler,
    "/admin/threads.json" -> new ThreadsHandler,
    "/admin/announcer" -> (new TextBlockView andThen new AnnouncerHandler),
    // "/admin/dtab" -> (new TextBlockView andThen new DtabHandler),
    "/admin/pprof/heap" -> new HeapResourceHandler,
    "/admin/pprof/profile" -> new ProfileResourceHandler(Thread.State.RUNNABLE),
    "/admin/pprof/contention" -> new ProfileResourceHandler(Thread.State.BLOCKED),
    "/admin/ping" -> new ReplyHandler("pong"),
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
  ).map {
      case (path, handler) =>
        path -> (new IndexView(path, path, () => Nil) andThen handler)
    }

  private[this] def metricsRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/admin/metrics.json" -> HttpMuxer,
    "/admin/per_host_metrics.json" -> HttpMuxer
  )

  def withIndexView(path: String, route: Route): Route =
    new IndexView(path, path, () => Nil).andThen(route)
}

class Admin(val address: SocketAddress) {
  import Admin._

  def serve(app: com.twitter.app.App, extRoutes: Admin.Routes): ListeningServer = {
    val muxer = (baseRoutes ++ appRoutes(app)).foldLeft(new HttpMuxer) {
      case (muxer, (path, handler)) =>
        log.debug(s"admin: $path => ${handler.getClass.getName}")
        muxer.withHandler(path, withIndexView(path, handler))
    }

    val service = extRoutes.foldLeft(muxer) {
      case (muxer, (path, handler)) =>
        log.debug(s"admin: $path => ${handler.getClass.getName}")
        muxer.withHandler(path, handler)
    }

    server.serve(address, notFoundView.andThen(service))
  }

  private[this] val notFoundView = new NotFoundView()
}
