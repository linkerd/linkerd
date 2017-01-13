package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future
import io.buoyant.admin.HtmlView
import io.buoyant.linkerd.Build

object AdminHandler extends HtmlView {

  val navBar =
    s"""<nav class="navbar navbar-inverse">
      <div class="navbar-container">
        <div class="navbar-header">
          <button type="button" class="navbar-toggle collapsed" data-toggle="collapse" data-target="#navbar" aria-expanded="false">
            <span class="sr-only">Toggle navigation</span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
            <span class="icon-bar"></span>
          </button>

          <a class="navbar-brand-img" href="/">
            <img alt="Logo" src="/files/images/linkerd-horizontal-white-transbg-vectorized.svg">
          </a>
        </div>
        <div id="navbar" class="collapse navbar-collapse">
          <ul class="nav navbar-nav">
            <li><a href="/delegator">dtab</a></li>
            <li><a href="/requests">requests</a></li>
            <li><a href="/admin/logging">logging</a></li>
            <li><a href="/help">help</a></li>
          </ul>
          <ul class="nav navbar-nav navbar-right">
            <li class="dropdown">
              <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-haspopup="true" aria-expanded="false">
                <span class="router-label">all</span> <span class="caret"></span>
              </a>
              <ul class="dropdown-menu">
              </ul>
            </li>
            <li>version ${Build.load().version}</li>
          </ul>
        </div>
      </div>
    </nav>"""

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
    navbar: String = navBar
  ): String = {
    val cssesHtml = csses.map { css =>
      s"""<link type="text/css" href="/files/css/$css" rel="stylesheet"/>"""
    }.mkString("\n")

    s"""
      <!doctype html>
      <html>
        <head>
          <title>linkerd admin</title>
          <link type="text/css" href="/files/css/lib/bootstrap.min.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/dashboard.css" rel="stylesheet"/>
          <link rel="shortcut icon" href="/files/images/favicon.png" />
          <link href='https://fonts.googleapis.com/css?family=Source+Sans+Pro:400,300,600' rel='stylesheet' type='text/css'>
          $cssesHtml
        </head>
        <body>
          $navbar

          <div class="container-fluid">
            $content
          </div>
          $tailContent

          <script data-main="/files/js/main-linkerd" src="/files/js/lib/require.js"></script>

        </body>
      </html>
    """
  }
}
