package io.buoyant.k8s.istio

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import istio.proxy.v1.config.RouteRule

/**
 * ApiserverClient is used to get route-rules from the Istio-Pilot apiserver.
 * https://github.com/istio/pilot/blob/master/apiserver/apiserver.go
 */
class ApiserverClient(
  client: Service[Request, Response],
  pollInterval: Duration
)(implicit timer: Timer = DefaultTimer.twitter) extends PollingApiClient(client) {
  import ApiserverClient._

  val Url = "/v1alpha1/config/route-rule"

  def getRouteRules: Future[Seq[RouteRuleConfig]] =
    get[Seq[RouteRuleConfig]](Url)
  val watchRouteRules: Activity[Seq[RouteRuleConfig]] =
    watch[Seq[RouteRuleConfig]](Url, pollInterval)
}

object ApiserverClient {
  trait IstioConfig[T] {
    def `type`: Option[String]
    def name: Option[String]
    def spec: Option[T]
  }

  case class RouteRuleConfig(
    `type`: Option[String],
    name: Option[String],
    spec: Option[RouteRule]
  ) extends IstioConfig[RouteRule]
}
