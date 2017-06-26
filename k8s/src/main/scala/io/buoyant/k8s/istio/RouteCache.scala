package io.buoyant.k8s.istio

import com.twitter.conversions.time._
import com.twitter.finagle.Http
import com.twitter.finagle.param.Label
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util._
import io.buoyant.k8s.SetHostFilter
import io.buoyant.k8s.istio.ApiserverClient.RouteRuleConfig
import io.buoyant.namer.RichActivity
import istio.proxy.v1.config.RouteRule

class RouteCache(api: ApiserverClient) extends Closable {

  private[this] def mkRouteMap(routeList: Seq[RouteRuleConfig]): Map[String, RouteRule] =
    routeList.collect {
      case RouteRuleConfig(typ, Some(name), Some(spec)) => name -> mkRouteRule(spec)
    }.toMap

  // workaround for https://github.com/FasterXML/jackson-module-scala/issues/87 :(
  private[this] def mkRouteRule(rr: RouteRule): RouteRule =
    if (rr.route == null) rr.copy(`route` = Seq.empty) else rr

  val routeRules: Activity[Map[String, RouteRule]] = api.watchRouteRules.map(mkRouteMap)

  def getRules: Future[Map[String, RouteRule]] = routeRules.toFuture

  // Hold the routeRules Activity open so that it doesn't get restarted for each call to getRules
  private[this] val closable = routeRules.states.respond(_ => ())

  override def close(deadline: Time): Future[Unit] = closable.close(deadline)
}

object RouteCache {
  private case class HostPort(host: String, port: Int)

  private val managers = Memoize { hp: HostPort =>
    val setHost = new SetHostFilter(hp.host, hp.port)
    val client = Http.client
      .withTracer(NullTracer)
      .withStreaming(false)
      .filtered(setHost)
      .configured(Label("istio-route-manager"))
    val api = new ApiserverClient(client.newService(s"/$$/inet/${hp.host}/${hp.port}"), 5.seconds)
    new RouteCache(api)
  }

  def getManagerFor(host: String, port: Int): RouteCache = managers(HostPort(host, port))
}
