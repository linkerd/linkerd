package io.buoyant.admin.names

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Dtab, Namer, Path, Service}
import com.twitter.util.Future
import io.buoyant.admin.HtmlView
import io.buoyant.namer.DelegatingNameInterpreter

class DelegateHandler(
  view: HtmlView,
  baseDtabs: Map[String, Dtab],
  interpreter: String => NameInterpreter
) extends Service[Request, Response] {

  def apply(req: Request): Future[Response] = {
    Future.collect {
      baseDtabs.map {
        case (ns, dtab) =>
          interpreter(ns) match {
            case delegating: DelegatingNameInterpreter =>
              delegating.dtab.values.toFuture.flatMap(Future.const)
                .map { interpreterDtab =>
                  ns -> interpreterDtab
                }
            case _ => Future.value(ns -> Dtab.empty)
          }
      }.toSeq
    }.map(_.toMap).map { interpreterDtabs =>
      render(interpreterDtabs, baseDtabs)
    }.flatMap(view.mkResponse(_))
  }

  val render = { (dtab: Map[String, Dtab], dtabBase: Map[String, Dtab]) =>
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
        <script id="dtab-data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtab)}</script>
        <script id="dtab-base-data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtabBase)}</script>
      """,
      javaScripts = Seq("dtab_viewer.js", "delegator.js", "delegate.js"),
      csses = Seq("styleguide/styleguide.css", "styleguide/local.css", "admin.css", "delegator.css")
    )
  }.tupled
}
