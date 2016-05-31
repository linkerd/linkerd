package io.buoyant.admin.names

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Dtab, Namer, Path, Service}
import com.twitter.util.Future
import io.buoyant.admin.HtmlView
import io.buoyant.linkerd.admin.names.DelegateApiHandler

class DelegateHandler(
  view: HtmlView,
  dtabs: () => Future[Map[String, Dtab]],
  namers: Seq[(Path, Namer)]
) extends Service[Request, Response] {

  def apply(req: Request): Future[Response] = {
    val dtabLocal = req.params.get("dtab.local") match {
      case Some(dtabStr) => Dtab.read(dtabStr)
      case None => Dtab.empty
    }
    dtabs().map { dtabs =>
      render(dtabs.mapValues(_ ++ dtabLocal))
    }.flatMap(view.mkResponse(_))
  }

  def render(dtab: Map[String, Dtab]) =
    view.html(
      content = s"""
        <div class="row">
          <div class="col-lg-6">
            <h2 class="router-label-title">Router</h2>
          </div>
        </div>
        <div class="delegator">
        </div>
      """,
      tailContent = s"""
        <script id="data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtab)}</script>
      """,
      javaScripts = Seq("dtab_viewer.js", "delegator.js", "delegate.js"),
      csses = Seq("styleguide/styleguide.css", "styleguide/local.css", "admin.css", "delegator.css")
    )
}
