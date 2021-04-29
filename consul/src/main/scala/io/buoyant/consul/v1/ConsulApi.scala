package io.buoyant.consul.v1

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{Backoff, http}
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.util.{Closable, Duration}

trait ConsulApi extends BaseApi {

  def serviceMap(
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[IndexedServiceMap]

  def serviceNodes(
    serviceName: String,
    datacenter: Option[String] = None,
    tag: Option[String] = None,
    blockingIndex: Option[String] = None,
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[IndexedServiceNodes]

}

object CatalogApi {
  def apply(c: Client): CatalogApi = new CatalogApi(c, s"/$versionString")
}

class CatalogApi(
  val client: Client,
  val uriPrefix: String,
  val backoffs: Backoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
  val stats: StatsReceiver = DefaultStatsReceiver
) extends ConsulApi with Closable {

  val catalogPrefix = s"$uriPrefix/catalog"

  // https://www.consul.io/docs/agent/http/catalog.html#catalog_datacenters
  def datacenters(retry: Boolean = false): ApiCall[Seq[String]] = ApiCall(
    req = mkreq(http.Method.Get, s"$catalogPrefix/datacenters", None),
    call = req => executeJson[Seq[String]](req, retry).map(_.value)
  )

  // https://www.consul.io/docs/agent/http/catalog.html#catalog_services
  def serviceMap(
    datacenter: Option[String] = None,
    blockingIndex: Option[String] = None,
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Indexed[Map[String, Seq[String]]]] = ApiCall(
    req = mkreq(
      http.Method.Get,
      s"$catalogPrefix/services",
      consistency,
      "index" -> blockingIndex,
      "dc" -> datacenter
    ),
    call = req => executeJson[Map[String, Seq[String]]](req, retry)
  )

  // https://www.consul.io/docs/agent/http/catalog.html#catalog_service
  def serviceNodes(
    serviceName: String,
    datacenter: Option[String] = None,
    tag: Option[String] = None,
    blockingIndex: Option[String] = None,
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Indexed[Seq[ServiceNode]]] = ApiCall(
    req = mkreq(
      http.Method.Get,
      s"$catalogPrefix/service/$serviceName",
      consistency,
      "index" -> blockingIndex,
      "dc" -> datacenter,
      "tag" -> tag
    ),
    call = req => executeJson[Seq[ServiceNode]](req, retry)
  )
}

object HealthApi {
  def apply(c: Client, statuses: Set[HealthStatus.Value]): HealthApi = new HealthApi(c, statuses, s"/$versionString")
}

class HealthApi(
  override val client: Client,
  val statuses: Set[HealthStatus.Value],
  override val uriPrefix: String,
  override val backoffs: Backoff = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
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
    consistency: Option[ConsistencyMode] = None,
    retry: Boolean = false
  ): ApiCall[Indexed[Seq[ServiceNode]]] = ApiCall(
    req = mkreq(
      http.Method.Get,
      s"$healthPrefix/service/$serviceName",
      consistency,
      "index" -> blockingIndex,
      "dc" -> datacenter,
      "tag" -> tag,
      // if passing=true only nodes with all health statuses in passing state are returned,
      // if passing=false no server side filtering is performed.
      "passing" -> Some((statuses == Set(HealthStatus.Passing)).toString)
    ),
    call = req => executeJson[Seq[ServiceHealth]](req, retry).map { indexed =>
      val result = indexed.value.map { health =>
        val service = health.Service
        val checks =
          for {
            checks <- health.Checks.toSeq
            check <- checks
            status <- check.Status
          } yield HealthStatus.withNameSafe(status)
        val node = health.Node

        ServiceNode(
          node.flatMap(_.Node),
          node.flatMap(_.Address),
          service.flatMap(_.ID),
          service.flatMap(_.Service),
          service.flatMap(_.Tags),
          service.flatMap(_.Address),
          service.flatMap(_.Port),
          checks.reduceOption(HealthStatus.worstCase),
          service.flatMap(_.Meta),
          node.flatMap(_.Meta)
        )
      }
      val nodes = if (statuses == Set(HealthStatus.Passing)) {
        result
      } else {
        result.filter(x => statuses.contains(x.Status.getOrElse(HealthStatus.Passing)))
      }
      Indexed[Seq[ServiceNode]](nodes, indexed.index)
    }
  )
}

case class Node(
  Node: Option[String],
  Address: Option[String],
  Meta: Option[Metadata]
)

case class Service_(
  ID: Option[String],
  Service: Option[String],
  Address: Option[String],
  Tags: Option[Seq[String]],
  Port: Option[Int],
  Meta: Option[Metadata]
)

case class ServiceHealth(
  Node: Option[Node],
  Service: Option[Service_],
  Checks: Option[Seq[Check]]
)

case class Check(
  Status: Option[String]
)

case class ServiceNode(
  Node: Option[String],
  Address: Option[String],
  ServiceID: Option[String],
  ServiceName: Option[String],
  ServiceTags: Option[Seq[String]],
  ServiceAddress: Option[String],
  ServicePort: Option[Int],
  Status: Option[HealthStatus.Value],
  ServiceMeta: Option[Metadata],
  NodeMeta: Option[Metadata]
)
