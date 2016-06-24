package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future

private object MetricsHandler extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = {
    AdminHandler.mkResponse(render)
  }

  val render =
    AdminHandler.html(
      content = s"""
        <div class="metrics">
        </div>
      """,
      javaScripts = Seq("lib/smoothie.js", "utils.js", "routers.js", "metrics.js"),
      csses = Seq("admin.css", "metrics.css")
    )
}
