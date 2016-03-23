package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.Service
import com.twitter.util.Future
import io.buoyant.linkerd.{Build, Linker}

private[admin] class DashboardHandler(linker: Linker) extends Service[Request, Response] {
  import DashboardHandler._

  lazy val html = summaryHtml(linker.routers.length)

  override def apply(req: Request): Future[Response] = req.path match {
    case "/dashboard" =>
      AdminHandler.mkResponse(html)
    case _ =>
      Future.value(Response(Status.NotFound))
  }
}

private object DashboardHandler {
  private[this] case class Stat(
    description: String,
    elemId: String,
    value: String,
    style: String,
    dataKey: String
  )

  def summaryHtml(routerCount: Int) = {
    val statsHtml =
      List(
        Stat("linkerd version", "linkerd-version", Build.load().version, "primary-stat", ""),
        Stat("router count", "router-count", routerCount.toString, "primary-stat", ""),
        Stat("uptime", "jvm-uptime", "0s", "", "jvm/uptime"),
        Stat("thread count", "jvm-thread-count", "0", "", "jvm/thread/count"),
        Stat("memory used", "jvm-mem-current-used", "0MB", "", "jvm/mem/current/used"),
        Stat("gc", "jvm-gc-msec", "1s", "", "jvm/gc/msec")
      ).map { stat =>
          s"""
        <li data-key="${stat.dataKey}">
          <strong class="stat-label ${stat.style}">${stat.description}</strong>
          <span id="${stat.elemId}" class="stat">${stat.value}</span>
        </li>
        """
        }.mkString("\n")

    AdminHandler.html(
      content = s"""
        <div class="row text-center">
          Welcome to the beta dashboard!
        </div>
        <hr>
        <div class="row text-center test-div">
        </div>
        <hr>
      """,
      csses = Seq("dashboard.css"),
      javaScripts = Seq("dashboard.js")
    )
  }
}