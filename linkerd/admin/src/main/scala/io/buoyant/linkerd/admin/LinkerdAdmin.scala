package io.buoyant.linkerd.admin

import com.twitter.app.App
import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.logging.Logger
import com.twitter.server.Admin.Path
import com.twitter.server.handler.{SummaryHandler => TSummaryHandler, _}
import com.twitter.server.view.{IndexView, TextBlockView}
import io.buoyant.linkerd.Linker

class LinkerdAdmin(app: App, linker: Linker) {

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
    "/admin/logging" -> new LoggingHandler,
    "/admin/metrics" -> new MetricQueryHandler,
    Path.Clients -> new ClientRegistryHandler(Path.Clients),
    Path.Servers -> new ServerRegistryHandler(Path.Servers),
    "/admin/files/" -> ResourceHandler.fromJar(
      baseRequestPath = "/admin/files/",
      baseResourcePath = "twitter-server"
    ),
    "/admin/registry.json" -> new RegistryHandler,
    "/favicon.ico" -> ResourceHandler.fromJar(
      baseRequestPath = "/",
      baseResourcePath = "twitter-server/img"
    )
  ).map {
      case (path, handler) =>
        path -> (new IndexView(path, path, () => Nil) andThen handler)
    }

  private[this] def linkerdAdminRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/" -> new SummaryHandler(linker),
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/linkerd/admin",
      localFilePath = "linkerd/admin/src/main/resources/io/buoyant/linkerd/admin"
    )),
    "/delegator" -> DelegateHandler.ui(linker),
    "/delegator.json" -> DelegateHandler.api,
    "/routers.json" -> new RouterHandler(linker),
    "/metrics" -> MetricsHandler
  )

  private[this] def metricsRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/admin/metrics.json" -> HttpMuxer,
    "/admin/per_host_metrics.json" -> HttpMuxer
  )

  def adminMuxer = {
    (twitterServerRoutes ++ linkerdAdminRoutes ++ metricsRoutes).foldLeft(new HttpMuxer) {
      case (muxer, (path, handler)) =>
        log.info(s"$path => ${handler.getClass.getName}")
        muxer.withHandler(path, handler)
    }
  }
}
