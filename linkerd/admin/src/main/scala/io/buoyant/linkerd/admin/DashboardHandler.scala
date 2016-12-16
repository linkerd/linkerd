package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.Service
import com.twitter.util.Future
import io.buoyant.linkerd.Build

private[admin] class DashboardHandler extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = req.path match {
    case "/" =>
      Option(req.getParam("router")) match {
        case Some(router) => AdminHandler.mkResponse(dashboardHtml(router))
        case None => AdminHandler.mkResponse(dashboardHtml())
      }
    case _ => Future.value(Response(Status.NotFound))
  }

  def dashboardHtml(router: String = "") = {
    AdminHandler.html(
      content = s"""
        <div class="request-totals"></div>
        <div class="server-data"
          data-linkerd-version="${Build.load().version}"
          data-router-name="${router}"
          style="visibility:hidden"></div>
        <div class="dashboard-container"></div>
        <div class="row proc-info">
        </div>
      """,
      javaScripts = Seq.empty
    )
  }
}
