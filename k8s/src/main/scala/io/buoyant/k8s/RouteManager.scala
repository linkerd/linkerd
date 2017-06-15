package io.buoyant.k8s

import com.twitter.util._
import io.buoyant.k8s.IstioPilotClient.RouteRuleConfig
import java.util.concurrent.atomic.AtomicReference
import istio.proxy.v1.config.RouteRule
import io.buoyant.namer.RichActivity

class RouteManager(api: IstioPilotClient, pollInterval: Duration) {
  private[this] val states: Var[Activity.State[Map[String, RouteRule]]] = api.watchRouteRules(pollInterval).run.map {
    case Activity.Ok(rsp) =>
      log.trace("istio route-rules received")
      Activity.Ok(mkRouteMap(rsp))
    case Activity.Pending =>
      log.trace("istio route manager is pending")
      Activity.Pending
    case Activity.Failed(e) =>
      log.error(e, "failed to update istio routing rules")
      Activity.Failed(e)
  }

  private[this] def mkRouteMap(routeList: Seq[RouteRuleConfig]): Map[String, RouteRule] =
    routeList.flatMap {
      case RouteRuleConfig(typ, Some(name), Some(spec)) => Some(name -> spec)
      case _ => None
    }.toMap[String, RouteRule]

  lazy val routeRules: Activity[Map[String, RouteRule]] = {
    val act = Activity(states)
    val _ = act.states.respond(_ => ()) // register a listener forever to keep the Activity open
    act
  }

  def getRules(): Future[Map[String, RouteRule]] = routeRules.toFuture
}
