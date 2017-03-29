package io.buoyant.namerd.iface

import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.{Dtab, Service}
import com.twitter.util.Future
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.namer.{Delegator, NamespacedInterpreterConfig, RichActivity}

case class DelegatorConfig(
  routerLabel: String,
  namespace: String,
  dtab: Dtab
)

class NamerdHandler(
  interpreterConfigs: Seq[(String, NamespacedInterpreterConfig)],
  namerdInterpreters: Map[String, Delegator]
) extends Service[Request, Response] {

  override def apply(req: Request): Future[Response] = {
    val delegatorConfigs: Seq[Future[DelegatorConfig]] = interpreterConfigs.flatMap {
      case (key, config) =>
        namerdInterpreters.get(key) match {
          case Some(delegator) =>
            val dtab = delegator.dtab.toFuture
            Some(dtab.map(DelegatorConfig(key, config.namespace.getOrElse("default"), _)))
          case None => None
        }
    }

    val collectedConfigs: Future[Seq[DelegatorConfig]] = Future.collect { delegatorConfigs }
    collectedConfigs.map(dashboardHtml)
  }

  private[this] def dashboardHtml(dtabs: Seq[DelegatorConfig]) = {
    val rsp = Response()
    rsp.contentType = MediaType.Html
    rsp.contentString = s"""
      <div class="container main">
        <div class="row">
          <h2>Namespaces</h2>
        </div>
        <div id="dtab-namespaces" class="row">
        </div>
        <div id="namerd-stats"></div>
      </div>
      <script id="dtab-data" type="application/json">${DelegateApiHandler.Codec.writeStr(dtabs)}</script>
      """
    rsp
  }
}
