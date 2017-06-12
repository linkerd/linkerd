package io.buoyant.k8s

import com.twitter.util._
import io.buoyant.k8s.PilotClient.RouteRuleConfig
import java.util.concurrent.atomic.AtomicReference
import istio.proxy.v1.config.RouteRule
import io.buoyant.namer.RichActivity

class RouteManager(api: PilotClient, pollInterval: Duration) {

  private[this] val states = Var.async[Activity.State[Map[String, RouteRule]]](Activity.Pending) { state =>
    val close = new AtomicReference[Closable](Closable.nop)
    close.set(Closable.make { _ =>
      log.trace("istio config watch was closed")
      Future.Unit
    })
    val pending = api.getRouteRules().respond {
      case Throw(e) => state.update(Activity.Failed(e))
      case Return(routeList) =>
        val initState: Map[String, RouteRule] = mkRouteMap(routeList)
        state.update(Activity.Ok(initState))

        val _ = api.watchRouteRules(pollInterval).run.map {
          case Activity.Ok(rsp) => state.update(Activity.Ok(mkRouteMap(rsp)))
          case Activity.Pending => //do nothing
          case Activity.Failed(e) => log.error(e, "failed to update istio routing rules")
        }
    }
    Closable.ref(close)
  }

  private[this] def mkRouteMap(routeList: Seq[RouteRuleConfig]): Map[String, RouteRule] =
    routeList.map(r => r.name.get -> r.spec.get).toMap[String, RouteRule]

  private[this] lazy val routeRules: Activity[Map[String, RouteRule]] = {
    val act = Activity(states)
    val _ = act.states.respond(_ => ()) // register a listener forever to keep the Activity open
    act
  }

  def getRules(): Future[Map[String, RouteRule]] = routeRules.toFuture
}
