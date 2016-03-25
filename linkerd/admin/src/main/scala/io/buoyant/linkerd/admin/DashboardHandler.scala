package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.Service
import com.twitter.util.Future
import io.buoyant.linkerd.Build

private[admin] class DashboardHandler extends Service[Request, Response] {
  lazy val html = dashboardHtml

  override def apply(req: Request): Future[Response] = req.path match {
    case "/dashboard" =>
      AdminHandler.mkResponse(html)
    case _ =>
      Future.value(Response(Status.NotFound))
  }

  def dashboardHtml = {
    AdminHandler.html(
      content = s"""
        <div class="server-data" data-linkerd-version="${Build.load().version}" style="visibility:hidden"></div>
        <div class="row text-center">
          Welcome to the beta dashboard!
        </div>
        <hr>
        <div class="row text-center test-div">
        </div>
        <hr>
      """,
      csses = Seq("dashboard.css"),
      javaScripts = Seq("utils.js", "process_info.js", "dashboard.js"),
      navbar = AdminHandler.version2NavBar
    )
  }
}
