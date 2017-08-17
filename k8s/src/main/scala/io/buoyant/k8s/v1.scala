package io.buoyant.k8s

import com.fasterxml.jackson.annotation.{JsonProperty, JsonSubTypes, JsonTypeInfo, JsonIgnore}
import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.{http, Service => FService}
import io.buoyant.k8s.{KubeObject => BaseObject}
import scala.collection.breakOut

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

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[ConfigMapAdded], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[ConfigMapModified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[ConfigMapDeleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[ConfigMapError], name = "ERROR")
  ))
  sealed trait ConfigMapWatch extends Watch[ConfigMap]
  case class ConfigMapAdded(
    `object`: ConfigMap
  ) extends ConfigMapWatch with Watch.Added[ConfigMap]

  case class ConfigMapModified(
    `object`: ConfigMap
  ) extends ConfigMapWatch with Watch.Modified[ConfigMap]

  case class ConfigMapDeleted(
    `object`: ConfigMap
  ) extends ConfigMapWatch with Watch.Deleted[ConfigMap]

  case class ConfigMapError(
    @JsonProperty(value = "object") status: Status
  ) extends ConfigMapWatch with Watch.Error[ConfigMap]

  implicit object ConfigMapDescriptor extends ObjectDescriptor[ConfigMap, ConfigMapWatch] {
    def listName = "configmaps"
    def toWatch(e: ConfigMap) = ConfigMapModified(e)
  }

  implicit private val endpointsListType = new TypeReference[EndpointsList] {}
  implicit private val serviceListType = new TypeReference[ServiceList] {}
  implicit private val endpointsType = new TypeReference[Endpoints] {}
  implicit private val serviceType = new TypeReference[Service] {}
  implicit private val configMapType = new TypeReference[ConfigMap] {}
  implicit private val endpointsWatch = new TypeReference[EndpointsWatch] {}
  implicit private val serviceWatch = new TypeReference[ServiceWatch] {}
  implicit private val configMapWatch = new TypeReference[ConfigMapWatch] {}

  case class Api(client: Client) extends Version[Object] {
    def group = v1.group
    def version = v1.version
    override def withNamespace(ns: String) = new NsApi(client, ns)
  }

  class NsApi(client: Client, ns: String)
    extends NsVersion[Object](client, v1.group, v1.version, ns) {
    def endpoints = listResource[Endpoints, EndpointsWatch, EndpointsList]()
    def endpoints(name: String): NsObjectResource[v1.Endpoints, v1.EndpointsWatch] = endpoints.named(name)
    def services = listResource[Service, ServiceWatch, ServiceList]()
    def service(name: String): NsObjectResource[Service, ServiceWatch] = services.named(name)
    def configMap(name: String) = objectResource[ConfigMap, ConfigMapWatch](name)
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
  ) extends Object {
    /**
     * @return the subsets list on this `Endpoints` object,
     *          or an empty [[Seq]] if it is empty.
     */
    @JsonIgnore
    @inline
    def subsetsSeq: Seq[EndpointSubset] =
      subsets.getOrElse(Seq.empty)

    @JsonIgnore
    @inline
    def getName: Option[String] =
      for {
        meta <- metadata
        name <- meta.name
      } yield name
  }

  case class EndpointSubset(
    notReadyAddresses: Option[Seq[EndpointAddress]] = None,
    addresses: Option[Seq[EndpointAddress]] = None,
    ports: Option[Seq[EndpointPort]] = None
  ) {
    @JsonIgnore
    @inline
    def addressesSeq: Seq[EndpointAddress] =
      addresses.getOrElse(Seq.empty)

    @JsonIgnore
    @inline
    def portsSeq: Seq[EndpointPort] =
      ports.getOrElse(Seq.empty)
  }

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
  ) extends Object {
    /**
     * @return a `Map[Int, String]` containing all the port to target port
     *         mappings in this `Service` object.
     */
    @JsonIgnore
    def portMappings: Map[Int, String] =
      (for {
        meta <- metadata.toSeq
        status <- status.toSeq
        spec <- spec.toSeq
        v1.ServicePort(port, targetPort, _) <- spec.ports
      } yield {
        port -> targetPort.getOrElse(port.toString)
      })(breakOut)

  }

  case class ServiceStatus(
    loadBalancer: Option[LoadBalancerStatus] = None
  )

  case class LoadBalancerStatus(
    ingress: Option[Seq[LoadBalancerIngress]]
  ) {
    @JsonIgnore
    @inline
    def ingressSeq: Seq[LoadBalancerIngress] = ingress.getOrElse(Seq.empty)
  }

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

  case class ConfigMap(
    data: Map[String, String],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends Object
}
