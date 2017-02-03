package io.buoyant.namerd

import com.twitter.finagle.http.{MediaType, Status, Response, Request}
import com.twitter.finagle.{Service, Dtab}
import com.twitter.util.Future
import io.buoyant.admin.names.DelegateApiHandler

class DtabHandler(
  store: DtabStore
) extends Service[Request, Response] {

  import DtabHandler._

  /** Get the dtab, if it exists. */
  private[this] def getDtab(ns: String): Future[Option[VersionedDtab]] =
    store.observe(ns).toFuture

  override def apply(req: Request): Future[Response] = req.path match {
    case rexp(namespace) =>
      getDtab(namespace).map {
        case Some(dtab) =>
          val response = Response()
          response.contentType = MediaType.Html + ";charset=UTF-8"
          response.contentString = render(namespace, dtab.dtab)
          response
        case None => Response(Status.NotFound)
      }
    case _ =>
      Future.value(Response(Status.NotFound))
  }

  def render(name: String, dtab: Dtab) =
    s"""
      <!doctype html>
      <html>
        <head>
          <title>namerd admin</title>
          <link rel="shortcut icon" href="../files/images/favicon.png" />
          <link type="text/css" href="../files/css/lib/bootstrap.min.css" rel="stylesheet"/>
          <link type="text/css" href="../files/css/fonts.css" rel="stylesheet"/>
          <link type="text/css" href="../files/css/dashboard.css" rel="stylesheet"/>
          <link type="text/css" href="../files/css/delegator.css" rel="stylesheet"/>
        </head>
        <body>
          <div class="container-fluid">
            <div class="row">
              <div class="col-lg-6">
                <h2 class="router-label-title">Namespace "$name"</h2>
              </div>
            </div>
            <div class="delegator">
            </div>
          </div>

          <script id="data" type="application/json">{"namespace": "$name", "dtab": ${DelegateApiHandler.Codec.writeStr(dtab)}}</script>
          <script data-main="../files/js/main-namerd" src="../files/js/lib/require.js"></script>
        </body>
      </html>
    """
}

object DtabHandler {
  val rexp = "/dtab/(.*)/?".r
}
