package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{HttpMuxer, MediaType, Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.server.TwitterServer
import com.twitter.server.handler.ResourceHandler
import com.twitter.server.view.NotFoundView
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
          <link type="text/css" href="/files/css/admin.css" rel="stylesheet"/>
          $cssesHtml
        </head>
        <body>
          <nav class="main-nav" id="main-nav">
            <a href="#" class="close-menu">&#x2715;</a>
            <a href="/">Summary</a>
            <a href="/delegator">Dtab</a>
            <a href="/admin">/admin</a>
            <a href="/metrics">Metrics</a>
            <a href="/admin/logging">Logs</a>
          </nav>

          <div class="page-wrap">
            <header class="main-header">
              <a href="#main-nav" class="open-menu">&#9776;</a>
              <h1>linkerd</h1>
              <button type="button" class="btn btn-default btn-lg" data-toggle="modal" data-target="#support-modal">
                Help Me
              </button>
            </header>
            <div class="container-fluid contents">
              $content
            </div>
          </div>

          <div class="modal fade" id="support-modal" tabindex="-1" role="dialog" aria-labelledby="support-modal-label">
            <div class="modal-dialog" role="document">
              <div class="modal-content">
                <div class="modal-header">
                  <button type="button" class="close" data-dismiss="modal" aria-label="Close"><span aria-hidden="true">&times;</span></button>
                  <h4 class="modal-title" id="support-modal-label">Support Request</h4>
                </div>
                <div class="modal-body">
                  <span id="help-block" class="help-block">
                    Please enter your email and a brief description of the issue you are seeing. Buoyant Engineering will contact you as soon as possible.
                  </span>

                  <form id="support-request" action="mailto:hello@buoyant.io" method="post" aria-describedby="help-block">
                    <div class="form-group">
                      <label for="email">Email address</label>
                      <input name="email" type="email" class="form-control" id="email" placeholder="Email">
                    </div>
                    <div class="form-group">
                      <label for="description">Description</label>
                      <textarea name="description" class="form-control" rows="5" id="description"></textarea>
                    </div>
                    <div class="form-group">
                      <label for="process-info">Process Info</label>
                      <textarea name="procinfo" class="form-control" id="support-procinfo" rows="3" readonly></textarea>
                    </div>
                  </form>

                </div>
                <div class="modal-footer">
                  <button type="button" class="btn btn-default" data-dismiss="modal">Cancel</button>
                  <button type="button" class="btn btn-primary" id="submit-support-request">Submit</button>
                </div>
              </div>
            </div>
          </div>

          $tailContent
          <script src="/files/js/lib/jquery.min.js"></script>
          <script src="/files/js/lib/bootstrap.min.js"></script>
          <script src="/files/js/admin.js"></script>
          $javaScriptsHtml
        </body>
      </html>
    """
  }

}

private class FlagsHandler(flags: String) extends Service[Request, Response] {
  override def apply(req: Request): Future[Response] = {
    AdminHandler.mkResponse(
      content = flags,
      mediaType = MediaType.Txt
    )
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

trait LinkerdAdmin
  extends DelegatorAdmin
  with MetricsAdmin { self: TwitterServer =>
  premain {
    log.info(s"Serving Linkerd Admin UI on ${adminPort()}")

    HttpMuxer.addHandler("/", new NotFoundView andThen SummaryHandler)
    HttpMuxer.addHandler("/flags", new FlagsHandler(
      flag.formattedFlagValuesString()
    ))
    HttpMuxer.addHandler(
      "/files/",
      StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
        baseRequestPath = "/files/",
        baseResourcePath = "io/buoyant/linkerd/admin",
        localFilePath = "linkerd/admin/src/main/resources/io/buoyant/linkerd/admin"
      )
    )
  }
}
