package io.buoyant.consul.v1

import com.twitter.conversions.time._
import com.twitter.finagle.http
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util.{Closable, Duration, Future}

object CatalogApi {
  def apply(c: Client): CatalogApi = new CatalogApi(c, s"/$versionString")
}

class CatalogApi(
  override val client: Client,
  override val uriPrefix: String,
  override val backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  override val stats: StatsReceiver = DefaultStatsReceiver
) extends BaseApi with Closable {

  val catalogPrefix = s"$uriPrefix/catalog"

  // https://www.consul.io/docs/agent/http/catalog.html#catalog_datacenters
  def datacenters(retry: Boolean = false): Future[Seq[String]] = {
    val req = mkreq(http.Method.Get, s"$catalogPrefix/datacenters")
    executeJson[Seq[String]](req, retry).map(_.value)
  }

  // https://www.consul.io/docs/agent/http/catalog.html#catalog_services
  def serviceMap(
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[Map[String, Seq[String]]]] = {
    val req = mkreq(
      http.Method.Get,
      s"$catalogPrefix/services",
      "index" -> blockingIndex,
      "dc" -> datacenter
    )
    executeJson[Map[String, Seq[String]]](req, retry)
  }

  // https://www.consul.io/docs/agent/http/catalog.html#catalog_service
  def serviceNodes(
    serviceName: String,
    datacenter: Option[String] = None,
    tag: Option[String] = None,
    blockingIndex: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[Seq[ServiceNode]]] = {
    val req = mkreq(
      http.Method.Get,
      s"$catalogPrefix/service/$serviceName",
      "index" -> blockingIndex,
      "dc" -> datacenter,
      "tag" -> tag
    )
    executeJson[Seq[ServiceNode]](req, retry)
  }
}

case class ServiceNode(
  Node: Option[String],
  Address: Option[String],
  ServiceID: Option[String],
  ServiceName: Option[String],
  ServiceTags: Option[Seq[String]],
  ServiceAddress: Option[String],
  ServicePort: Option[Int]
)
