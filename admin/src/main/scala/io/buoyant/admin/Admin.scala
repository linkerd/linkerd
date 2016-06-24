package io.buoyant.admin

import com.twitter.app.{App => TApp}
import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.finagle.stats.MetricsStatsReceiver
import com.twitter.logging.Logger
import com.twitter.server.Admin.Path
import com.twitter.server.handler.{SummaryHandler => TSummaryHandler, _}
import com.twitter.server.view.{IndexView, TextBlockView}

class Admin(app: TApp) {

  private[this] val log = Logger()

  private[this] def twitterServerRoutes: Seq[(String, Service[Request, Response])] = Seq(
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
    "/admin/metrics" -> new MetricsQueryHandler,
    "/admin/metrics/prometheus" -> new PrometheusStatsHandler(MetricsStatsReceiver.defaultRegistry),
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

  def allRoutes = twitterServerRoutes ++ metricsRoutes

  def adminMuxer = {
    allRoutes.foldLeft(new HttpMuxer) {
      case (muxer, (path, handler)) =>
        log.info(s"$path => ${handler.getClass.getName}")
        muxer.withHandler(path, handler)
    }
  }
}
