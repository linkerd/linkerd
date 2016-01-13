package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{HttpMuxer, MediaType, Request, Response, Status}
import com.twitter.finagle.{Http => FHttp, Service, http => fhttp, param}
import com.twitter.io.Buf
import com.twitter.server.TwitterServer
import com.twitter.util.Future
import io.buoyant.linkerd.Build
import scala.util.matching.Regex

private object SummaryHandler extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = req.path match {
    case "/" =>
      AdminHandler.mkResponse(summaryHtml)
    case _ =>
      Future.value(Response(Status.NotFound))
  }

  private[this] case class Stat(
    description: String,
    elemId: String,
    value: String,
    style: String,
    dataKey: String
  )

  lazy val summaryHtml = {

    val statsHtml =
      List(
        Stat("linkerd version", "linkerd-version", Build.load().version, "primary-stat", ""),
        Stat("router count", "router-count", "1", "primary-stat", ""),
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

    AdminHandler.adminHtml(
      content = s"""
        <div class="row text-center">
          <div id="process-info" data-refresh-uri="/admin/metrics">
            <ul class="list-inline topline-stats">
              $statsHtml
            </ul>
          </div>
        </div>
        <hr/>

        <h3 class="header text-center">Request Volume</h3>
        <div class="row text-center requests">
          <div id="request-stats" class="col-sm-2 dl-horizontal"></div>
          <div class="col-sm-10">
            <canvas id="request-canvas" height="200"></canvas>
          </div>
        </div>

        <div id="client-info" class="interfaces"></div>
        <div id="server-info" class="interfaces"></div>
      """,
      csses = Seq("summary.css"),
      javaScripts = Seq("lib/handlebars-v4.0.5.js", "lib/smoothie.js", "utils.js", "summary.js")
    )
  }
}
