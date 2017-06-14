package io.buoyant.k8s

import com.fasterxml.jackson.annotation.{JsonProperty, JsonSubTypes, JsonTypeInfo}
import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.{http, Service => FService}
import io.buoyant.k8s.{KubeObject => BaseObject}

package object v1 {

  type Client = FService[http.Request, http.Response]

  val group = "api"
  val version = "v1"

  trait Object extends BaseObject

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[EndpointsAdded], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[EndpointsModified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[EndpointsDeleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[EndpointsError], name = "ERROR")
  ))
  sealed trait EndpointsWatch extends Watch[Endpoints]
  case class EndpointsAdded(
    `object`: Endpoints
  ) extends EndpointsWatch with Watch.Added[Endpoints]

  case class EndpointsModified(
    `object`: Endpoints
  ) extends EndpointsWatch with Watch.Modified[Endpoints]

  case class EndpointsDeleted(
    `object`: Endpoints
  ) extends EndpointsWatch with Watch.Deleted[Endpoints]

  case class EndpointsError(
    @JsonProperty(value = "object") status: Status
  ) extends EndpointsWatch with Watch.Error[Endpoints]

  implicit object EndpointsDescriptor extends ObjectDescriptor[Endpoints, EndpointsWatch] {
    def listName = "endpoints"
    def toWatch(e: Endpoints) = EndpointsModified(e)
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[ServiceAdded], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[ServiceModified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[ServiceDeleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[ServiceError], name = "ERROR")
  ))
  sealed trait ServiceWatch extends Watch[Service]
  case class ServiceAdded(
    `object`: Service
  ) extends ServiceWatch with Watch.Added[Service]

  case class ServiceModified(
    `object`: Service
  ) extends ServiceWatch with Watch.Modified[Service]

  case class ServiceDeleted(
    `object`: Service
  ) extends ServiceWatch with Watch.Deleted[Service]

  case class ServiceError(
    @JsonProperty(value = "object") status: Status
  ) extends ServiceWatch with Watch.Error[Service]

  implicit object ServiceDescriptor extends ObjectDescriptor[Service, ServiceWatch] {
    def listName = "services"
    def toWatch(e: Service) = ServiceModified(e)
  }

  implicit private val endpointsListType = new TypeReference[EndpointsList] {}
  implicit private val serviceListType = new TypeReference[ServiceList] {}
  implicit private val endpointsType = new TypeReference[Endpoints] {}
  implicit private val serviceType = new TypeReference[Service] {}
  implicit private val endpointsWatch = new TypeReference[EndpointsWatch] {}
  implicit private val serviceWatch = new TypeReference[ServiceWatch] {}

  case class Api(client: Client) extends Version[Object] {
    def group = v1.group
    def version = v1.version
    override def withNamespace(ns: String) = new NsApi(client, ns)
    def endpoints = listResource[Endpoints, EndpointsWatch, EndpointsList]()
    def services = listResource[Service, ServiceWatch, ServiceList]()
  }

  class NsApi(client: Client, ns: String)
    extends NsVersion[Object](client, v1.group, v1.version, ns) {
    def endpoints = listResource[Endpoints, EndpointsWatch, EndpointsList]()
    def services = listResource[Service, ServiceWatch, ServiceList]()
  }

  case class EndpointsList(
    items: Seq[Endpoints],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends KubeList[Endpoints]

  case class Endpoints(
    subsets: Option[Seq[EndpointSubset]] = None,
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends Object

  case class EndpointSubset(
    notReadyAddresses: Option[Seq[EndpointAddress]] = None,
    addresses: Option[Seq[EndpointAddress]] = None,
    ports: Option[Seq[EndpointPort]] = None
  )

  case class EndpointAddress(
    ip: String,
    nodeName: Option[String] = None,
    targetRef: Option[ObjectReference] = None
  )

  case class EndpointPort(
    port: Int,
    name: Option[String] = None,
    protocol: Option[String] = None
  )

  case class ServiceList(
    items: Seq[Service],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends KubeList[Service]

  case class Service(
    status: Option[ServiceStatus] = None,
    spec: Option[ServiceSpec] = None,
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends Object

  case class ServiceStatus(
    loadBalancer: Option[LoadBalancerStatus] = None
  )

  case class LoadBalancerStatus(
    ingress: Option[Seq[LoadBalancerIngress]]
  )

  case class LoadBalancerIngress(
    ip: Option[String] = None,
    hostname: Option[String] = None
  )

  case class ServiceSpec(
    ports: Seq[ServicePort]
  )

  case class ServicePort(
    port: Int,
    targetPort: Option[String],
    name: String
  )
}
