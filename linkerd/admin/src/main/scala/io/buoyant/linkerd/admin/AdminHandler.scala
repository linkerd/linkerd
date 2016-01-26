package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future

object AdminHandler {

  def mkResponse(
    content: String,
    mediaType: String = MediaType.Html
  ): Future[Response] = {
    val response = Response()
    response.contentType = mediaType + ";charset=UTF-8"
    response.contentString = content
    Future.value(response)
  }

  def adminHtml(
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
          $cssesHtml
        </head>
        <body>
          <nav class="navbar navbar-inverse navbar-fixed-top">
            <ul class="nav navbar-nav navbar-right">
              <li class="dropdown hide">
                <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" aria-haspopup="true" aria-expanded="false">
                  <span class="router-label">All</span> <span class="caret"></span>
                </a>
                <ul class="dropdown-menu">
                </ul>
              </li>
            </ul>
            <div class="container">
              <div class="navbar-header">
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

object StaticFilter extends SimpleFilter[Request, Response] {

  private[this] val contentTypes: Map[String, String] = Map(
    "css" -> "text/css",
    "gif" -> MediaType.Gif,
    "html" -> MediaType.Html,
    "jpeg" -> MediaType.Jpeg,
    "jpg" -> MediaType.Jpeg,
    "js" -> MediaType.Javascript,
    "json" -> MediaType.Json,
    "png" -> MediaType.Png,
    "svg" -> "image/svg+xml",
    "txt" -> MediaType.Txt
  )

  def apply(req: Request, svc: Service[Request, Response]) = {
    svc(req).flatMap { res =>
      val ext = req.path.split('.').lastOption.getOrElse("")
      val contentType = contentTypes.getOrElse(ext, "application/octet-stream")
      res.contentType = contentType + ";charset=UTF-8"
      Future.value(res)
    }
  }
}
