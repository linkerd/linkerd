package io.buoyant.namerd

import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.{Namer, Path, Service, SimpleFilter}
import com.twitter.server.handler.ResourceHandler
import com.twitter.util.Future
import io.buoyant.admin.Admin.{Handler, NavItem}
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.admin.{Admin, ConfigHandler, HtmlView, LoggingApiHandler, LoggingHandler, StaticFilter}
import io.buoyant.namer.ConfiguredNamersInterpreter

object NamerdAdmin {

  val static: Seq[Handler] = Seq(
    Handler("/files/", (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )))
  )

  def config(nc: NamerdConfig) = Seq(
    Handler("/config.json", new ConfigHandler(nc, NamerdConfig.LoadedInitializers.iter))
  )

  def dtabs(dtabStore: DtabStore, namers: Map[Path, Namer], adminFilter: NamerdFilter) = Seq(
      Handler("/", adminFilter.andThen(new DtabListHandler(dtabStore))),
      Handler("/dtab/delegator.json", new DelegateApiHandler(_ => ConfiguredNamersInterpreter(namers.toSeq))),
      Handler("/dtab/", adminFilter.andThen(new DtabHandler(dtabStore)))
    )

  def logging(adminHandler: NamerdAdmin, adminFilter: NamerdFilter): Seq[Handler] = Seq(
      Handler("/logging.json", new LoggingApiHandler()),
      Handler("/logging", adminFilter.andThen(new LoggingHandler(adminHandler)))
    )

  def apply(nc: NamerdConfig, namerd: Namerd): Seq[Handler] = {
    val handler = new NamerdAdmin(Seq(NavItem("logging", "logging")))
    val adminFilter = new NamerdFilter(handler)

    static ++ config(nc) ++
      dtabs(namerd.dtabStore, namerd.namers, adminFilter) ++ logging(handler, adminFilter) ++
      Admin.extractHandlers(namerd.dtabStore +: (namerd.namers.values.toSeq ++ namerd.telemeters))
  }

}

private class NamerdAdmin(navItems: Seq[NavItem]) extends HtmlView {
  private[this] def navHtml(highlightedItem: String = "") = navItems.map { item =>
    val activeClass = if (item.name == highlightedItem) "active" else ""
    s"""<li class=$activeClass><a href="/${item.url}">${item.name}</a></li>"""
  }.mkString("\n")

  override def html(
    content: String,
    tailContent: String,
    csses: Seq[String],
    navHighlight: String,
    showRouterDropdown: Boolean
  ): String =
    s"""
<!doctype html>
      <html>
        <head>
          <title>namerd admin</title>
          <link type="text/css" href="/files/css/lib/bootstrap.min.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/fonts.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/dashboard.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/logger.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/delegator.css" rel="stylesheet"/>
          <link rel="shortcut icon" href="/files/images/favicon.png" />
        </head>
        <body>
          <nav class="navbar navbar-inverse">
      <div class="navbar-container">
        <div class="navbar-header">
          <button type="button" class="navbar-toggle collapsed" data-toggle="collapse" data-target="#navbar" aria-expanded="false">
            <span class="sr-only">Toggle navigation</span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
          </button>

          <a class="navbar-brand" href="/">
            <div>Namerd</div>
          </a>
        </div>
        <div id="navbar" class="collapse navbar-collapse">
          <ul class="nav navbar-nav">
          ${navHtml(navHighlight)}
          </ul>
        </div>
      </div>
    </nav>

          <div class="container-fluid">
            $content
          </div>
          $tailContent

          <script data-main="/files/js/main-linkerd" src="/files/js/lib/require.js"></script>

        </body>
      </html>
     """

  override def mkResponse(
    content: String,
    mediaType: String
  ): Future[Response] = {
    val response = Response()
    response.contentType = mediaType + ";charset=UTF-8"
    response.contentString = content
    Future.value(response)
  }
}

private class NamerdFilter(
  adminHandler: NamerdAdmin, css: Seq[String] = Nil
) extends SimpleFilter[Request, Response] {
  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = {
    service(request).map { rsp =>
      val itemToHighlight = request.path.replace("/", "")
      if (rsp.contentType.contains(MediaType.Html))
        rsp.contentString = adminHandler
          .html(rsp.contentString, csses = css, navHighlight = itemToHighlight)
      rsp
    }
  }
}
