package io.buoyant.k8s

import com.fasterxml.jackson.databind.DeserializationFeature
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.config.Parser

// TODO: All Istio-Pilot clients are very similar.  DRY them up and re-use the underlying client
class RdsClient(client: Service[Request, Response]) {
  import RdsClient._

  private[this] val log = Logger()

  private[this] val mapper = Parser.jsonObjectMapper(Nil)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  def get(): Future[Seq[RouteConfig]] = {
    val req = Request(s"/v1/routes")
    client(req).map { rsp =>
      mapper.readValue[Seq[RouteConfig]](rsp.contentString)
    }
  }

  def watch(
    pollInterval: Duration
  )(implicit timer: Timer = DefaultTimer.twitter): Activity[Seq[RouteConfig]] = {
    val state = Var.async[Activity.State[Seq[RouteConfig]]](Activity.Pending) { update =>

      def doUpdate() = get().respond {
        case Return(rsp) => update.update(Activity.Ok(rsp))
        case Throw(e) =>
          log.error(e, "RDS update failed")
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

object RdsClient {
  case class RouteConfig(virtual_hosts: Seq[VHost])
  case class VHost(name: String, domains: Seq[String])
}
