package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Dtab, Service}
import com.twitter.util.Future
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.namer.{Delegator, NamespacedInterpreterConfig}

case class DelegatorConfig(
  routerLabel: String,
  namespace: String,
  dtab: Dtab
)

class NamerdHandler(
  view: AdminHandler,
  interpreterConfigs: Seq[(String, NamespacedInterpreterConfig)],
  namerdInterpreters: Map[String, Delegator]
) extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = {
    val delegatorConfigs: Seq[Future[DelegatorConfig]] = interpreterConfigs.flatMap {
      case (key, config) =>
        namerdInterpreters.get(key) match {
          case Some(delegator) =>
            val dtab = delegator.dtab.values.toFuture().flatMap(Future.const)
            Some(dtab.map(DelegatorConfig(key, config.namespace.get, _)))
          case None => None
        }
    }

    val collectedConfigs: Future[Seq[DelegatorConfig]] = Future.collect { delegatorConfigs }
    collectedConfigs.flatMap(d => view.mkResponse(dashboardHtml(d)))
  }

  def dashboardHtml(dtabs: Seq[DelegatorConfig]) = {
    view.html(
      content = s"""
      <div class="container main">
        <div class="row">
          <h2>Namespaces</h2>
        </div>
        <div id="dtab-namespaces" class="row">
        </div>
        <div id="namerd-stats"></div>
      </div>
      """,
      tailContent = s"""
        <script id="dtab-data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtabs)}</script>
      """,
      csses = Seq("delegator.css")
    )
  }
}
