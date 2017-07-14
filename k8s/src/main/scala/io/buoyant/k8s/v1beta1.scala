package io.buoyant.k8s

import com.fasterxml.jackson.annotation.{JsonProperty, JsonSubTypes, JsonTypeInfo}
import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.{http, Service => FService}
import io.buoyant.k8s.{KubeObject => BaseObject}

package object v1beta1 {

  type Client = FService[http.Request, http.Response]

  val group = "apis"
  val version = "extensions/v1beta1"

  trait Object extends BaseObject

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[IngressAdded], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[IngressModified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[IngressDeleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[IngressError], name = "ERROR")
  ))
  sealed trait IngressWatch extends Watch[Ingress]
  case class IngressAdded(
    `object`: Ingress
  ) extends IngressWatch with Watch.Added[Ingress]

  case class IngressModified(
    `object`: Ingress
  ) extends IngressWatch with Watch.Modified[Ingress]

  case class IngressDeleted(
    `object`: Ingress
  ) extends IngressWatch with Watch.Deleted[Ingress]

  case class IngressError(
    @JsonProperty(value = "object") status: Status
  ) extends IngressWatch with Watch.Error[Ingress]

  implicit object IngressDescriptor extends ObjectDescriptor[Ingress, IngressWatch] {
    def listName = "ingresses"
    def toWatch(e: Ingress) = IngressModified(e)
  }

  implicit private val ingressListType = new TypeReference[IngressList] {}
  implicit private val ingressType = new TypeReference[Ingress] {}
  implicit private val ingressWatch = new TypeReference[IngressWatch] {}

  case class Api(client: Client) extends Version[Object] {
    def group = v1beta1.group
    def version = v1beta1.version
    override def withNamespace(ns: String) = new NsApi(client, ns)
    def ingresses = listResource[Ingress, IngressWatch, IngressList]()
  }

  class NsApi(client: Client, ns: String)
    extends NsVersion[Object](client, v1beta1.group, v1beta1.version, ns) {
    def ingresses = listResource[Ingress, IngressWatch, IngressList]()
  }

  case class IngressList(
    items: Seq[Ingress],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends KubeList[Ingress]

  case class Ingress(
    spec: Option[IngressSpec] = None,
    status: Option[IngressStatus] = None,
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  ) extends Object

  case class IngressStatus(
    loadBalancer: Option[LoadBalancerStatus] = None
  )

  case class IngressSpec(
    backend: Option[IngressBackend] = None,
    tls: Option[Seq[IngressTLS]],
    rules: Option[Seq[IngressRule]]
  )

  case class IngressBackend(
    serviceName: String,
    servicePort: String
  )

  case class IngressTLS(
    hosts: Option[Seq[String]],
    secretName: Option[String]
  )

  case class IngressRule(
    host: Option[String],
    http: Option[HTTPIngressRuleValue]
  )

  case class HTTPIngressRuleValue(
    paths: Seq[HTTPIngressPath]
  )

  case class HTTPIngressPath(
    path: Option[String],
    backend: IngressBackend
  )

  case class LoadBalancerStatus(
    ingress: Option[Seq[LoadBalancerIngress]]
  )

  case class LoadBalancerIngress(
    ip: Option[String] = None,
    hostname: Option[String] = None
  )

}
