package io.buoyant.consul.v1

import com.twitter.conversions.time._
import com.twitter.finagle.http
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util.{Closable, Duration, Future}

trait ConsulApi extends BaseApi {
  def serviceMap(
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[Map[String, Seq[String]]]]

  def serviceNodes(
    serviceName: String,
    datacenter: Option[String] = None,
    tag: Option[String] = None,
    blockingIndex: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[Seq[ServiceNode]]]
}

object CatalogApi {
  def apply(c: Client): CatalogApi = new CatalogApi(c, s"/$versionString")
}

class CatalogApi(
  val client: Client,
  val uriPrefix: String,
  val backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  val stats: StatsReceiver = DefaultStatsReceiver
) extends ConsulApi with Closable {

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

object HealthApi {
  def apply(c: Client): HealthApi = new HealthApi(c, s"/$versionString")
}

class HealthApi(
  override val client: Client,
  override val uriPrefix: String,
  override val backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  override val stats: StatsReceiver = DefaultStatsReceiver
) extends CatalogApi(
  client,
  uriPrefix,
  backoffs,
  stats
) with Closable {

  val healthPrefix = s"$uriPrefix/health"

  // https://www.consul.io/docs/agent/http/health.html#health_service
  override def serviceNodes(
    serviceName: String,
    datacenter: Option[String] = None,
    tag: Option[String] = None,
    blockingIndex: Option[String] = None,
    retry: Boolean = false
  ): Future[Indexed[Seq[ServiceNode]]] = {
    val req = mkreq(
      http.Method.Get,
      s"$healthPrefix/service/$serviceName",
      "index" -> blockingIndex,
      "dc" -> datacenter,
      "tag" -> tag,
      "passing" -> Some("true")
    )
    executeJson[Seq[ServiceHealth]](req, retry).map { indexed =>
      val result = indexed.value.map { health =>
        val service = health.Service
        val node = health.Node
        ServiceNode(
          node.flatMap(_.Node),
          node.flatMap(_.Address),
          service.flatMap(_.ID),
          service.flatMap(_.Service),
          service.flatMap(_.Tags),
          service.flatMap(_.Address),
          service.flatMap(_.Port)
        )
      }
      Indexed[Seq[ServiceNode]](result, indexed.index)
    }
  }
}

case class Node(
  Node: Option[String],
  Address: Option[String]
)

case class Service_(
  ID: Option[String],
  Service: Option[String],
  Address: Option[String],
  Tags: Option[Seq[String]],
  Port: Option[Int]
)

case class ServiceHealth(
  Node: Option[Node],
  Service: Option[Service_]
)

case class ServiceNode(
  Node: Option[String],
  Address: Option[String],
  ServiceID: Option[String],
  ServiceName: Option[String],
  ServiceTags: Option[Seq[String]],
  ServiceAddress: Option[String],
  ServicePort: Option[Int]
)
