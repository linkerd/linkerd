package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Dtab, Service}
import com.twitter.util.Future
import io.buoyant.linkerd.admin.names.DelegateApiHandler

private[admin] class DtabHandler(
  dtabs: () => Future[Map[String, Dtab]]
) extends Service[Request, Response] {

  import DtabHandler._

  override def apply(req: Request): Future[Response] = dtabs().flatMap { dtabMap =>
    req.path match {
      case rexp(routerName) => dtabMap.get(routerName) match {
        case Some(dtab) => (AdminHandler.mkResponse(render(routerName, dtab)))
        case None => Future.value(Response(Status.NotFound))
      }
      case _ =>
        Future.value(Response(Status.NotFound))
    }
  }

  def render(name: String, dtab: Dtab) =
    AdminHandler.html(
      content = s"""
        <div class="row">
          <div class="col-lg-6">
            <h2 class="router-label-title">Router "$name"</h2>
          </div>
        </div>
        <div class="delegator">
        </div>
      """,
      tailContent = s"""
        <script id="data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtab)}</script>
      """,
      javaScripts = Seq("dtab_viewer.js", "delegator.js", "dashboard_delegate.js"),
      csses = Seq("dashboard.css", "delegator.css"),
      navbar = AdminHandler.version2NavBar
    )
}

object DtabHandler {
  val rexp = "/dtab/(.*)/?".r
}
