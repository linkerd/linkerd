package io.buoyant.admin

import com.twitter.app.{App => TApp}
import com.twitter.finagle._
import com.twitter.finagle.buoyant._
import com.twitter.finagle.http.filter.HeadFilter
import com.twitter.finagle.http.{HttpMuxer, Request, Response}
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.logging.Logger
import com.twitter.server.handler.{SummaryHandler => _, _}
import com.twitter.server.view.{NotFoundView, TextBlockView}
import com.twitter.util.Monitor
import io.buoyant.config.types.Port
import java.net.{InetSocketAddress, SocketAddress}

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

  private def makeServer(tls: Option[TlsServerConfig]) =
    Http.server
      .withLabel(label)
      .withMonitor(loggingMonitor)
      .withStatsReceiver(NullStatsReceiver)
      .withTracer(NullTracer)
      .maybeWith(tls.map(_.params(None, Netty4ServerEngineFactory())))

  val threadsJs = "<script src='files/js/threads.js'></script>"

  def appHandlers(app: TApp): Seq[Handler] = Seq(
    Handler("/admin/server_info", new TextBlockView().andThen(new ServerInfoHandler(app))),
    Handler("/admin/shutdown", new ShutdownHandler(app))
  )

  def baseHandlers: Seq[Handler] = Seq(
    Handler("/admin/contention", new TextBlockView andThen new ContentionHandler),
    Handler("/admin/lint", new AdminAssetsFilter andThen new LintHandler),
    Handler("/admin/lint.json", new LintHandler),
    Handler("/admin/threads", new AdminAssetsFilter(threadsJs) andThen new ThreadsHandler),
    Handler("/admin/threads.json", new ThreadsHandler),
    Handler("/admin/announcer", new TextBlockView andThen new AnnouncerHandler),
    Handler("/admin/pprof/heap", new HeapResourceHandler),
    Handler("/admin/pprof/profile", new ProfileResourceHandler(Thread.State.RUNNABLE)),
    Handler("/admin/pprof/contention", new ProfileResourceHandler(Thread.State.BLOCKED)),
    Handler("/admin/ping", new ReplyHandler("pong")),
    Handler("/admin/tracing", new TracingHandler),
    Handler("/admin/registry.json", new RegistryHandler),
    Handler("/admin/files/", StaticFilter.andThen(ResourceHandler.fromDirectoryOrJar( // for Admin handlers
      baseRequestPath = "/admin/files/",
      baseResourcePath = "io/buoyant/admin/twitter-server",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    ))),
    Handler("/favicon.ico", StaticFilter.andThen(ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/",
      baseResourcePath = "io/buoyant/admin/twitter-server/images",
      localFilePath = "admin/src/main/resources/io/buoyant/admin/twitter-server/images"
    )))
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

class Admin(val address: InetSocketAddress, tlsCfg: Option[TlsServerConfig]) {
  import Admin._

  private[this] val notFoundView = new NotFoundView()
  private[this] val server = makeServer(tlsCfg)

  /**
   * Whether or not this admin service was configured to serve over TLS
   */
  val isTls: Boolean = tlsCfg.isDefined

  /**
   * the name of the scheme the admin page is served over
   */
  val scheme: String = if (this.isTls) "https" else "http"

  def mkService(app: TApp, extHandlers: Seq[Handler]): Service[Request, Response] = {
    val handlers = baseHandlers ++ appHandlers(app) ++ extHandlers
    val muxer = (handlers ++ indexHandlers(handlers)).foldLeft(new HttpMuxer) {
      case (muxer, Handler(url, service, _)) =>
        log.debug(s"admin: $url => ${service.getClass.getName}")
        muxer.withHandler(url, service)
    }
    HeadFilter andThen notFoundView andThen muxer
  }

  def serve(app: TApp, extHandlers: Seq[Handler]): ListeningServer =
    server.serve(address, mkService(app, extHandlers))

  def serveHandler(port: Int, handler: Handler): ListeningServer = {
    val addrWithPort = new InetSocketAddress(address.getAddress, port)
    val muxer = new HttpMuxer().withHandler(handler.url, handler.service)
    makeServer(tlsCfg).serve(addrWithPort, muxer)
  }
}
