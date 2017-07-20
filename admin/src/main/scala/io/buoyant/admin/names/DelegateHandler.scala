package io.buoyant.admin.names

import com.twitter.conversions.time._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Dtab, Service}
import com.twitter.util.{Future, TimeoutException}
import io.buoyant.admin.HtmlView
import io.buoyant.namer.{Delegator, RichActivity}

class DelegateHandler(
  view: HtmlView,
  baseDtabs: Map[String, Dtab],
  interpreter: String => NameInterpreter
) extends Service[Request, Response] {

  private[this] implicit val time = DefaultTimer

  def apply(req: Request): Future[Response] = {

    val dtabs = Future.collect {
      baseDtabs.keys.map(getInterpreterDtab).toSeq
    }

    dtabs.map(_.toMap).map { interpreterDtabs =>
      render(interpreterDtabs, baseDtabs)
    }.within(2.seconds).handle {
      case e: TimeoutException => timeoutContent
    }.flatMap(view.mkResponse(_))
  }

  private[this] def getInterpreterDtab(ns: String): Future[(String, Dtab)] =
    interpreter(ns) match {
      case delegator: Delegator =>
        delegator.dtab.toFuture.map(ns -> _)
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
      navHighlight = "dtab",
      tailContent = s"""
        <script id="dtab-data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtab)}</script>
        <script id="dtab-base-data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtabBase)}</script>
      """,
      csses = Seq("delegator.css"),
      showRouterDropdown = true
    )
  }.tupled

  val timeoutContent = view.html(
    content = s"""
        <div class="row">
          <div class="col-lg-6">
            <h2 class="router-label-title">Router</h2>
            <p>The request to namerd has timed out.  Please ensure your config is correct and try again.</p>
          </div>
        </div>
      """,
    csses = Seq("delegator.css"),
    showRouterDropdown = true
  )
}
