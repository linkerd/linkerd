package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{MediaType, Response}
import com.twitter.util.Future
import io.buoyant.admin.Admin.NavItem
import io.buoyant.admin.{Build, HtmlView}

class AdminHandler(navItems: Seq[NavItem]) extends HtmlView {

  private[this] val build = Build.load("/io/buoyant/linkerd/build.properties")

  private[this] def navHtml(highlightedItem: String = "") = navItems.map { item =>
    val activeClass = if (item.name == highlightedItem) "active" else ""
    s"""<li class=$activeClass><a href="${item.url}">${item.name}</a></li>"""
  }.mkString("\n")

  def navBar(highlightedItem: String = "", showRouterDropdown: Boolean = false) = {
    val routerDropdown = if (showRouterDropdown) {
      """
      <li class="dropdown">
        <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-haspopup="true" aria-expanded="false">
          <span class="router-label">all</span> <span class="caret"></span>
        </a>
        <ul class="dropdown-menu">
        </ul>
      </li>
      """
    } else ""

    s"""<nav class="navbar navbar-inverse">
      <div class="navbar-container">
        <div class="navbar-header">
          <button type="button" class="navbar-toggle collapsed" data-toggle="collapse" data-target="#navbar" aria-expanded="false">
            <span class="sr-only">Toggle navigation</span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
          </button>

          <a class="navbar-brand" href=".">
            <img alt="Logo" src="files/images/linkerd-horizontal-white-transbg-vectorized.svg">
          </a>
        </div>
        <div id="navbar" class="collapse navbar-collapse">
          <ul class="nav navbar-nav">
          ${navHtml(highlightedItem)}
          </ul>
          <ul class="nav navbar-nav navbar-right">
            ${routerDropdown}
            <li class="version">version ${build.version}</li>
          </ul>
        </div>
      </div>
    </nav>"""
  }

  def mkResponse(
    content: String,
    mediaType: String = MediaType.Html
  ): Future[Response] = {
    val response = Response()
    response.contentType = mediaType + ";charset=UTF-8"
    response.contentString = content
    Future.value(response)
  }

  def html(
    content: String,
    tailContent: String = "",
    csses: Seq[String] = Nil,
    navHighlight: String = "",
    showRouterDropdown: Boolean = false
  ): String = {
    val cssesHtml = csses.map { css =>
      s"""<link type="text/css" href="files/css/$css" rel="stylesheet"/>"""
    }.mkString("\n")

    s"""
      <!doctype html>
      <html>
        <head>
          <title>linkerd admin</title>
          <link type="text/css" href="files/css/lib/bootstrap.min.css" rel="stylesheet"/>
          <link type="text/css" href="files/css/fonts.css" rel="stylesheet"/>
          <link type="text/css" href="files/css/dashboard.css" rel="stylesheet"/>
          <link type="text/css" href="files/css/logger.css" rel="stylesheet"/>
          <link rel="shortcut icon" href="files/images/favicon.png" />
          $cssesHtml
        </head>
        <body>
          ${navBar(navHighlight, showRouterDropdown)}

          <div class="container-fluid">
            $content
          </div>
          $tailContent

          <script data-main="files/js/main-linkerd" src="files/js/lib/require.js"></script>

        </body>
      </html>
    """
  }
}
