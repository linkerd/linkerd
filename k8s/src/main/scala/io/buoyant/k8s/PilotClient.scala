package io.buoyant.k8s

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.config.Parser
import istio.proxy.v1.config.RouteRule

class PilotClient(client: Service[Request, Response]) {
  import PilotClient._

  private[this] val log = Logger()
  private[this] val mapper = Parser.jsonObjectMapper(Nil)
  private[this] val version = "v1alpha1"

  // from https://github.com/istio/pilot/blob/master/apiserver/apiserver.go
  def getRouteRules(): Future[Seq[RouteRuleConfig]] = {
    val req = Request(s"/v1alpha1/config/route-rule")
    client(req).map { rsp =>
      mapper.readValue[Seq[RouteRuleConfig]](rsp.contentString)
    }
  }

  def watchRouteRules(
    pollInterval: Duration
  )(implicit timer: Timer = DefaultTimer.twitter): Activity[Seq[RouteRuleConfig]] = {
    val state = Var.async[Activity.State[Seq[RouteRuleConfig]]](Activity.Pending) { update =>

      def doUpdate() = getRouteRules().respond {
        case Return(rsp) => update.update(Activity.Ok(rsp))
        case Throw(e) =>
          log.error(e, "failed to watch istio routing rules")
          update.update(Activity.Failed(e))
      }

      val _ = doUpdate()

      timer.schedule(pollInterval) {
        val _ = doUpdate()
      }
    }
    Activity(state)
  }
}

object PilotClient {
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
