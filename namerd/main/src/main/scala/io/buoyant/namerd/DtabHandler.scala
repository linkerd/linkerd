package io.buoyant.namerd

import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.finagle.{Dtab, Service}
import com.twitter.util.{Future, Return, Throw}
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.namer.RichActivity

class DtabHandler(
  store: DtabStore
) extends Service[Request, Response] {

  import DtabHandler._

  /** Get the dtab, if it exists. */
  private[this] def getDtab(ns: String): Future[Option[VersionedDtab]] =
    store.observe(ns).toFuture

  override def apply(req: Request): Future[Response] = req.path match {
    case rexp(namespace) =>
      getDtab(namespace).transform {
        case Return(None) => Future.value(Response(Status.NotFound))
        case Return(Some(dtab)) =>
          renderDtab(namespace, dtab.dtab)
        case Throw(e) =>
          renderError(e)
      }
    case _ =>
      Future.value(Response(Status.NotFound))
  }

  def renderDtab(name: String, dtab: Dtab): Future[Response] = {
    val body = s"""
            <div class="row">
              <div class="col-lg-6">
                <h2 class="router-label-title">Namespace "$name"</h2>
              </div>
            </div>
            <div class="delegator">
            </div>

          <script id="data" type="application/json">{"namespace": "$name", "dtab": ${
      DelegateApiHandler
        .Codec.writeStr(dtab)
    }}</script>
          <script data-main="../files/js/main-namerd" src="../files/js/lib/require.js"></script>
      """
    render(body)
  }

  def renderError(e: Throwable): Future[Response] = {
    val body = s"""
            <div class="row">
              <div class="col-lg-6">
                <h2 class="router-label-title">Dtab Error</h2>
                <p>An error was encountered when fetching or parsing the dtab:</p>
                <pre>$e</pre>
              </div>
            </div>
    """
    render(body)
  }

  def render(body: String): Future[Response] = {
    val response = Response()
    response.contentType = MediaType.Html
    response.contentString = body
    Future.value(response)
  }
}

object DtabHandler {
  val rexp = "/dtab/(.*)/?".r
}
