package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future

private object MetricsHandler extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = {
    AdminHandler.mkResponse(render)
  }

  val render =
    AdminHandler.adminHtml(
      content = s"""
        <div class="metrics">
          <div class="row">
            <div class="metrics-names col-sm-3"></div>
            <div class="metrics-graph col-sm-9">
              <div id="metrics-title">
                <span class="name">&nbsp;</span>
                <span class="value stat">&nbsp;</span>
              </div>
              <canvas id="metrics-canvas" height="300"></canvas>
            </div>
          </div>
          <div class="row metrics-json">
            <span>Raw data: </span>
            <a href="/admin/metrics.json">/admin/metrics.json</a>
          </div>
        </div>
      """,
      javaScripts = Seq("lib/smoothie.js", "utils.js", "metrics.js"),
      csses = Seq("metrics.css")
    )
}
