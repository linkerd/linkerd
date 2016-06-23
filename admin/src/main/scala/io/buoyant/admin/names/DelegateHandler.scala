package io.buoyant.admin.names

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Dtab, Namer, Path, Service}
import com.twitter.util.Future
import io.buoyant.admin.HtmlView
import io.buoyant.namer.Delegator

class DelegateHandler(
  view: HtmlView,
  baseDtabs: Map[String, Dtab],
  interpreter: String => NameInterpreter
) extends Service[Request, Response] {

  def apply(req: Request): Future[Response] = {

    val dtabs = Future.collect {
      baseDtabs.keys.map(getInterpreterDtab).toSeq
    }

    dtabs.map(_.toMap).map { interpreterDtabs =>
      render(interpreterDtabs, baseDtabs)
    }.flatMap(view.mkResponse(_))
  }

  private[this] def getInterpreterDtab(ns: String): Future[(String, Dtab)] =
    interpreter(ns) match {
      case delegator: Delegator =>
        delegator.dtab.values.toFuture.flatMap(Future.const).map(ns -> _)
      case _ => Future.value(ns -> Dtab.empty)
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
      csses = Seq("delegator.css")
    )
  }.tupled
}
