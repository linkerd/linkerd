package io.buoyant.namerd

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.Future

class DtabListHandler(
  store: DtabStore
) extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] =
    store.list().toFuture.map { list =>
      val response = Response()
      response.contentType = MediaType.Html + ";charset=UTF-8"
      response.contentString = render(list)
      response
    }

  def render(list: Set[String]) = {
    val listHtml = list.map { ns =>
      s"""
        <a class="list-group-item" href="dtab/$ns">$ns</a>
      """
    }.mkString("")

    s"""
      <!doctype html>
      <html>
        <head>
          <title>namerd admin</title>
          <link rel="shortcut icon" href="/files/images/favicon.png" />
          <link type="text/css" href="/files/css/lib/bootstrap.min.css" rel="stylesheet"/>
          <link type="text/css" href="/files/css/dashboard.css" rel="stylesheet"/>
        </head>
        <body>
          <div class="container-fluid">
            <div class="row">
              <div class="col-lg-6">
                <h2 class="router-label-title">Namespaces:</h2>
                <div class="list-group">
                  $listHtml
                </div>
              </div>
            </div>
          </div>
        </body>
      </html>
    """
  }
}
