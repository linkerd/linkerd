package io.buoyant.k8s

import com.fasterxml.jackson.annotation.{JsonProperty, JsonSubTypes, JsonTypeInfo}
import com.twitter.finagle.{Service, http}
import io.buoyant.k8s.{KubeObject => BaseObject, _}

package object v1 {

  type Client = Service[http.Request, http.Response]

  val group = "api"
  val version = "v1"

  trait Object extends BaseObject

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Added], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Modified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Deleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Error], name = "ERROR")
  ))
  sealed trait EndpointsWatch extends Watch[Endpoints]
  object EndpointsWatch {
    case class Added(
      `object`: Endpoints
    ) extends EndpointsWatch with Watch.Added[Endpoints]

    case class Modified(
      `object`: Endpoints
    ) extends EndpointsWatch with Watch.Modified[Endpoints]

    case class Deleted(
      `object`: Endpoints
    ) extends EndpointsWatch with Watch.Deleted[Endpoints]

    case class Error(
      @JsonProperty(value = "object") status: Status
    ) extends EndpointsWatch with Watch.Error[Endpoints]
  }

  implicit object EndpointsDescriptor extends ObjectDescriptor[Endpoints, EndpointsWatch] {
    def listName = "endpoints"
    def toWatch(e: Endpoints) = EndpointsWatch.Modified(e)
  }

  case class Api(client: Client) extends Version[Object] {
    def group = v1.group
    def version = v1.version
    override def withNamespace(ns: String) = new NsApi(client, ns)
    def endpoints = listResource[Endpoints, EndpointsWatch, EndpointsList]()
  }

  class NsApi(client: Client, ns: String)
    extends NsVersion[Object](client, v1.group, v1.version, ns) {
    def endpoints = listResource[Endpoints, EndpointsWatch, EndpointsList]()
  }

  case class EndpointsList(
    items: Seq[Endpoints],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends KubeList[Endpoints]

  case class Endpoints(
    subsets: Seq[EndpointSubset],
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
    targetRef: Option[ObjectReference] = None
  )

  case class EndpointPort(
    port: Int,
    name: Option[String] = None,
    protocol: Option[String] = None
  )
}
