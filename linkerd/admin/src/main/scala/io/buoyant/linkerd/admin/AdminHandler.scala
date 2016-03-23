package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future
import io.buoyant.admin.HtmlView

object AdminHandler extends HtmlView {

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
    javaScripts: Seq[String] = Nil
  ): String = {
    val cssesHtml = csses.map { css =>
      s"""<link type="text/css" href="/files/css/$css" rel="stylesheet"/>"""
    }.mkString("\n")

    val javaScriptsHtml = javaScripts.map { js =>
      s"""<script src="/files/js/$js"></script>"""
    }.mkString("\n")

    s"""
      <!doctype html>
      <html>
        <head>
          <title>linkerd admin</title>
          <link type="text/css" href="/files/css/lib/bootstrap.min.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/styleguide/styleguide.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/styleguide/local.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/admin.css" rel="stylesheet"/>
          <link rel="shortcut icon" href="/favicon.png" />
          $cssesHtml
        </head>
        <body>
          <nav class="navbar navbar-inverse navbar-fixed-top">
            <div class="container">
              <div class="navbar-header">
                <button type="button" class="navbar-toggle collapsed" data-toggle="collapse" data-target="#navbar" aria-expanded="false">
                  <span class="sr-only">Toggle navigation</span>
                  <span class="icon-bar"></span>
                  <span class="icon-bar"></span>
                  <span class="icon-bar"></span>
                </button>

                <a class="navbar-brand-img" href="/">
                  <img alt="Logo" src="/files/images/logo.svg">
                </a>
              </div>
              <div id="navbar" class="collapse navbar-collapse">
                <ul class="nav navbar-nav">
                  <li><a href="/delegator">dtab</a></li>
                  <li><a href="/metrics">metrics</a></li>
                  <li><a href="/admin/logging">logging</a></li>
                  <li><a href="https://linkerd.io/help/">help</a></li>
                  <!-- <li><a href="/dashboard">Beta</a></li> -->
                </ul>

                <ul class="nav navbar-nav navbar-right">
                  <li class="dropdown">
                    <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-haspopup="true" aria-expanded="false">
                      <span class="router-label">All</span> <span class="caret"></span>
                    </a>
                    <ul class="dropdown-menu">
                    </ul>
                  </li>
                </ul>
              </div>
            </div>
          </nav>

          <div class="container-fluid">
            $content
          </div>

          $tailContent

          <script src="/files/js/lib/jquery.min.js"></script>
          <script src="/files/js/lib/bootstrap.min.js"></script>
          <script src="/files/js/lib/lodash.min.js"></script>
          <script src="/files/js/lib/handlebars-v4.0.5.js"></script>
          <script src="/files/js/admin.js"></script>
          $javaScriptsHtml
        </body>
      </html>
    """
  }
}
