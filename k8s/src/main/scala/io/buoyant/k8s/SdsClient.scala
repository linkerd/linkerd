package io.buoyant.k8s

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.config.Parser
import io.buoyant.k8s.SdsClient.SdsResponse

/**
  * A client for talking to the Service Discovery Service.
  * https://lyft.github.io/envoy/docs/configuration/cluster_manager/sds_api.html
  */
class SdsClient(client: Service[Request, Response], clusterSuffix: String = "svc.cluster.local") {

  private[this] val log = Logger()

  private[this] val mapper = Parser.jsonObjectMapper(Nil)

  def get(ns: String, port: String, service: String, labels: Map[String, String]): Future[SdsResponse] = {
    val selectors = Seq(port) ++ labels.map { case (k, v) => s"$k=$v" }
    val req = Request(s"/v1/registration/$service.$ns.$clusterSuffix|${selectors.mkString("|")}")
    client(req).map { rsp =>
      mapper.readValue[SdsResponse](rsp.contentString)
    }
  }

  def watch(
    ns: String,
    port: String,
    service: String,
    labels: Map[String, String],
    pollInterval: Duration
  )(implicit timer: Timer = DefaultTimer.twitter): Activity[SdsResponse] = {
    val state = Var.async[Activity.State[SdsResponse]](Activity.Pending) { update =>

      def doUpdate() = get(ns, port, service, labels).respond {
        case Return(rsp) => update.update(Activity.Ok(rsp))
        case Throw(e) =>
          log.error(e, "SDS update failed")
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

object SdsClient {
  case class SdsResponse(hosts: Seq[SdsEndpoint])
  case class SdsEndpoint(ip_address: String, port: Int)
}
