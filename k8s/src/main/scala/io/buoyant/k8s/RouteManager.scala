package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.Http
import com.twitter.finagle.param.Label
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util._
import io.buoyant.k8s.IstioPilotClient.RouteRuleConfig
import io.buoyant.namer.RichActivity
import istio.proxy.v1.config.RouteRule

class RouteManager(api: IstioPilotClient, pollInterval: Duration) {
  private[this] val states: Activity[Map[String, RouteRule]] = api.watchRouteRules(pollInterval).map(mkRouteMap)

  private[this] def mkRouteMap(routeList: Seq[RouteRuleConfig]): Map[String, RouteRule] =
    routeList.collect {
      case RouteRuleConfig(typ, Some(name), Some(spec)) => name -> spec
    }.toMap

  private[this] lazy val routeRules: Activity[Map[String, RouteRule]] = {
    val _ = states.states.respond(_ => ()) // register a listener forever to keep the Activity open
    states
  }

  def getRules(): Future[Map[String, RouteRule]] = routeRules.toFuture
}

object RouteManager {
  private[this] val managers = Var(Map.empty[(String, Int), RouteManager])

  def getManagerFor(host: String, port: Int): RouteManager = {
    synchronized {
      val sample = managers.sample()
      sample.collectFirst { case ((h, p), manager) if h == h && p == p => manager }.getOrElse {
        val setHost = new SetHostFilter(host, port)
        val client = Http.client
          .withTracer(NullTracer)
          .withStreaming(true)
          .filtered(setHost)
          .configured(Label("istio-route-manager"))
        val api = new IstioPilotClient(client.newService(s"/$$/inet/$host/$port"))
        val routeManager = new RouteManager(api, 5.seconds) //TODO: make port configurable
        val entry = (host, port) -> routeManager
        managers.update(sample + entry)
        routeManager
      }
    }
  }
}
