package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future

private object MetricsHandler extends Service[Request, Response] {

  def apply(req: Request): Future[Response] =
    AdminHandler.mkResponse(render)

  val render =
    AdminHandler.html(
      content = """<div class="metrics"></div>""",
      csses = Seq("admin.css", "metrics.css")
    )
}
