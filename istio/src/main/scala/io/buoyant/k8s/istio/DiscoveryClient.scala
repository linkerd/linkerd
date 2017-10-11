package io.buoyant.k8s.istio

import com.twitter.conversions.time._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Http, Service}
import com.twitter.util._
import io.buoyant.k8s.SetHostFilter

/**
 * A client for talking to the Istio-Pilot discovery service.  This includes the Service Discovery
 * Service (SDS) and the Route Discovery Service (RDS)
 * https://lyft.github.io/envoy/docs/configuration/cluster_manager/sds_api.html
 * https://lyft.github.io/envoy/docs/configuration/http_conn_man/rds.html#rest-api
 */
class DiscoveryClient(
  client: Service[Request, Response],
  pollingInterval: Duration
) extends PollingApiClient(client) {
  import DiscoveryClient._

  def getService(cluster: String, port: String, labels: Map[String, String]): Future[SdsResponse] = {
    val selectors = Seq(port) ++ labels.map { case (k, v) => s"$k=$v" }
    get[SdsResponse](s"/v1/registration/$cluster|${selectors.mkString("|")}")
  }
  def watchService(
    cluster: String,
    port: String,
    labels: Map[String, String]
  )(implicit timer: Timer = DefaultTimer): Activity[SdsResponse] = {
    val selectors = Seq(port) ++ labels.map { case (k, v) => s"$k=$v" }
    watch[SdsResponse](s"/v1/registration/$cluster|${selectors.mkString("|")}", pollingInterval)
  }

  def getRoutes: Future[Seq[RouteConfig]] = get[Seq[RouteConfig]]("/v1/routes")
  val watchRoutes: Activity[Seq[RouteConfig]] = watch[Seq[RouteConfig]]("/v1/routes", pollingInterval)
}

object DiscoveryClient {
  private case class HostPort(host: String, port: Int)

  private val clients = Memoize { hp: HostPort =>
    val setHost = new SetHostFilter(hp.host, hp.port)
    val client = Http.client
      .withTracer(NullTracer)
      .withStreaming(false)
      .filtered(setHost)
      .newService(s"/$$/inet/${hp.host}/${hp.port}", "istio-discovery")
    new DiscoveryClient(client, 5.seconds)
  }

  def apply(host: String, port: Int): DiscoveryClient = clients(HostPort(host, port))

  case class SdsResponse(hosts: Seq[SdsEndpoint])
  case class SdsEndpoint(ip_address: String, port: Int)

  case class RouteConfig(virtual_hosts: Seq[VHost])
  case class VHost(name: String, domains: Seq[String])
}
