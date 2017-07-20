package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.Service
import com.twitter.util.Future
import io.buoyant.admin.Build

private[admin] class DashboardHandler(adminHandler: AdminHandler) extends Service[Request, Response] {

  private[this] val build = Build.load("/io/buoyant/linkerd/build.properties")

  override def apply(req: Request): Future[Response] = req.path match {
    case "/" =>
      Option(req.getParam("router")) match {
        case Some(router) => adminHandler.mkResponse(dashboardHtml(router))
        case None => adminHandler.mkResponse(dashboardHtml())
      }
    case "/services" => adminHandler.mkResponse(dashboardHtml())
    case _ => Future.value(Response(Status.NotFound))
  }

  def dashboardHtml(router: String = "") = {
    adminHandler.html(
      content = s"""
        <div class="request-totals"></div>
        <div class="server-data"
          data-linkerd-version="${build.version}"
          data-router-name="${router}"
          style="visibility:hidden"></div>
        <div class="client-dashboard-container dashboard-container"></div>
        <div class="service-dashboard-container dashboard-container"></div>
        <div class="row proc-info">
        </div>
      """,
      showRouterDropdown = true
    )
  }
}
