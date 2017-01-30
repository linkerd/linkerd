package io.buoyant.admin

import com.twitter.app.{App => TApp}
import com.twitter.finagle._
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.handler.{SummaryHandler => _, _}
import com.twitter.server.view.{NotFoundView, TextBlockView}
import com.twitter.util.Monitor
import java.net.SocketAddress

object Admin {
  val label = "adminhttp"
  private val log = Logger.get(label)

  case class Handler(url: String, service: Service[Request, Response], css: Seq[String] = Nil)

  /**
   * A type for modules that expose admin handlers.
   */
  trait WithHandlers {
    def adminHandlers: Seq[Handler]
  }

  def getHandlers(obj: AnyRef): Seq[Handler] =
    obj match {
      case wh: WithHandlers => wh.adminHandlers
      case _ => Nil
    }

  def extractHandlers(objs: Seq[AnyRef]): Seq[Handler] =
    objs.flatMap(getHandlers(_))

  case class NavItem(name: String, url: String)
  trait WithNavItems {
    def navItems: Seq[NavItem]
  }

  def getNavItems(obj: AnyRef): Seq[NavItem] = obj match {
    case withNav: WithNavItems => withNav.navItems
    case _ => Nil
  }

  def extractNavItems(objs: Seq[AnyRef]): Seq[NavItem] =
    objs.flatMap(getNavItems)

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

  def appHandlers(app: TApp): Seq[Handler] = Seq(
    Handler("/admin/server_info", new TextBlockView().andThen(new ServerInfoHandler(app))),
    Handler("/admin/shutdown", new ShutdownHandler(app))
  )

  def baseHandlers: Seq[Handler] = Seq(
    Handler("/admin/contention", new TextBlockView andThen new ContentionHandler),
    Handler("/admin/lint", new LintHandler),
    Handler("/admin/lint.json", new LintHandler),
    Handler("/admin/threads", new ThreadsHandler),
    Handler("/admin/threads.json", new ThreadsHandler),
    Handler("/admin/announcer", new TextBlockView andThen new AnnouncerHandler),
    Handler("/admin/pprof/heap", new HeapResourceHandler),
    Handler("/admin/pprof/profile", new ProfileResourceHandler(Thread.State.RUNNABLE)),
    Handler("/admin/pprof/contention", new ProfileResourceHandler(Thread.State.BLOCKED)),
    Handler("/admin/ping", new ReplyHandler("pong")),
    Handler("/admin/tracing", new TracingHandler),
    Handler("/admin/registry.json", new RegistryHandler),
    Handler("/favicon.png", ResourceHandler.fromJar(
      baseRequestPath = "/",
      baseResourcePath = "io/buoyant/linkerd/admin/images"
    ))
  )

  /** Generate an index of the provided handlers */
  private def indexHandlers(handlers: Seq[Handler]): Seq[Handler] = {
    val paths = handlers.map(_.url).sorted.distinct
    val index = new IndexTxtHandler(paths)
    Seq(
      Handler("/admin/index.txt", index),
      Handler("/admin", index)
    )
  }
}

class Admin(val address: SocketAddress) {
  import Admin._

  private[this] val notFoundView = new NotFoundView()

  def mkService(app: TApp, extHandlers: Seq[Handler]): Service[Request, Response] = {
    val handlers = baseHandlers ++ appHandlers(app) ++ extHandlers
    val muxer = (handlers ++ indexHandlers(handlers)).foldLeft(new HttpMuxer) {
      case (muxer, Handler(url, service, _)) =>
        log.debug(s"admin: $url => ${service.getClass.getName}")
        muxer.withHandler(url, service)
    }
    notFoundView.andThen(muxer)
  }

  def serve(app: TApp, extHandlers: Seq[Handler]): ListeningServer =
    server.serve(address, mkService(app, extHandlers))
}
