package io.buoyant.k8s.istio

import com.twitter.util.Duration

sealed class MixerAttributeType(name: String)

case object StringAttribute extends MixerAttributeType("string")

case object Int64Attribute extends MixerAttributeType("int64")

case object StringMapAttribute extends MixerAttributeType("map[string, string]")

case object IpAddressAttribute extends MixerAttributeType("ip_address")

case object TimestampAttribute extends MixerAttributeType("timestamp")

case object DurationAttribute extends MixerAttributeType("duration")

/**
 * From the [https://istio.io/docs/concepts/policy-and-control/attributes.html: Istio documentation]
 *
 * > Istio uses attributes to control the runtime behavior of services running in the service mesh. Attributes are named and
 * > typed pieces of metadata describing ingress and egress traffic and the environment this traffic occurs in. An Istio attribute
 * > carries a specific piece of information such as the error code of an API request, the latency of an API request, or the original
 * > IP address of a TCP connection
 *
 * @param name
 * @param attributeType
 * @param description
 */
sealed abstract class IstioAttribute[T](val name: String, val attributeType: MixerAttributeType, description: String) {
  val value: T
}

case class SourceIpIstioAttribute(val value: String) extends IstioAttribute[String]("source.ip", IpAddressAttribute, "Client IP address.	10.0.0.117")

case class SourceServiceIstioAttribute(val value: String) extends IstioAttribute[String]("source.service", StringAttribute, "The fully qualified name of the service that the client belongs to.	redis-master.my-namespace.svc.cluster.local")

case class SourceNameIstioAttribute(val value: String) extends IstioAttribute[String]("source.name", StringAttribute, "The short name part of the source service.	redis-master")

case class SourceNamespaceIstioAttribute(val value: String) extends IstioAttribute[String]("source.namespace", StringAttribute, "The namespace part of the source service.	my-namespace")

case class SourceDomainIstioAttribute(val value: String) extends IstioAttribute[String]("source.domain", StringAttribute, "The domain suffix part of the source service, excluding the name and the namespace.	svc.cluster.local")

case class SourceUidIstioAttribute(val value: String) extends IstioAttribute[String]("source.uid", StringAttribute, "Platform-specific unique identifier for the client instance of the source service.	kubernetes://redis-master-2353460263-1ecey.my-namespace")

case class SourceLabelIstioAttribute(val value: Map[String, String]) extends IstioAttribute[Map[String, String]]("source.labels", StringMapAttribute, "A map of key-value pairs attached to the client instance.	version => v1")

case class SourceUserIstioAttribute(val value: String) extends IstioAttribute[String]("source.user", StringAttribute, "The identity of the immediate sender of the request, authenticated by mTLS.	service-account-foo")

case class TargetIpIstioAttribute(val value: String) extends IstioAttribute[String]("target.ip", IpAddressAttribute, "Server IP address.	10.0.0.104")

case class TargetPortIstioAttribute(val value: Long) extends IstioAttribute[Long]("target.port", Int64Attribute, "The recipient port on the server IP address.	8080")

case class TargetServiceIstioAttribute(val value: String) extends IstioAttribute[String]("target.service", StringAttribute, "The fully qualified name of the service that the server belongs to.	my-svc.my-namespace.svc.cluster.local")

case class TargetNameIstioAttribute(val value: String) extends IstioAttribute[String]("target.name", StringAttribute, "The short name part of the target service.	my-svc")

case class TargetNamespaceIstioAttribute(val value: String) extends IstioAttribute[String]("target.namespace", StringAttribute, "The namespace part of the target service.	my-namespace")

case class TargetDomainIstioAttribute(val value: String) extends IstioAttribute[String]("target.domain", StringAttribute, "The domain suffix part of the target service, excluding the name and the namespace.	svc.cluster.local)")

case class TargetUidIstioAttribute(val value: String) extends IstioAttribute[String]("target.uid", StringAttribute, "Platform-specific unique identifier for the server instance of the target service.	kubernetes://my-svc-234443-5sffe.my-namespace")

case class TargetLabelsIstioAttribute(val value: Map[String, String]) extends IstioAttribute[Map[String, String]]("target.labels", StringMapAttribute, "A map of key-value pairs attached to the server instance.	version => v2")

case class TargetUserIstioAttribute(val value: String) extends IstioAttribute[String]("target.user", StringAttribute, "The user running the target application.	service-account")

case class RequestHeadersIstioAttribute(val value: Map[String, String]) extends IstioAttribute[Map[String, String]]("request.headers", StringMapAttribute, "HTTP request headers.")

case class RequestIdIstioAttribute(val value: String) extends IstioAttribute[String]("request.id", StringAttribute, "An ID for the request with statistically low probability of collision.")

case class RequestPathIstioAttribute(val value: String) extends IstioAttribute[String]("request.path", StringAttribute, "The HTTP URL path including query string")

case class RequestHostIstioAttribute(val value: String) extends IstioAttribute[String]("request.host", StringAttribute, "HTTP/1.x host header or HTTP/2 authority header.	redis-master:3337")

case class RequestMethodIstioAttribute(val value: String) extends IstioAttribute[String]("request.method", StringAttribute, "The HTTP method.")

case class RequestReasonIstioAttribute(val value: String) extends IstioAttribute[String]("request.reason", StringAttribute, "The request reason used by auditing systems.")

case class RequestRefererIstioAttribute(val value: String) extends IstioAttribute[String]("request.referer", StringAttribute, "The HTTP referer header.")

case class RequestSchemeIstioAttribute(val value: String) extends IstioAttribute[String]("request.scheme", StringAttribute, "URI Scheme of the request")

case class RequestSizeIstioAttribute(val value: Long) extends IstioAttribute[Long]("request.size", Int64Attribute, "Size of the request in bytes. For HTTP requests this is equivalent to the Content-Length header.")

case class RequestTimeIstioAttribute(val value: Long) extends IstioAttribute[Long]("request.time", TimestampAttribute, "The timestamp when the target receives the request. This should be equivalent to Firebase now.")

case class RequestUseragentIstioAttribute(val value: String) extends IstioAttribute[String]("request.useragent", StringAttribute, "The HTTP User-Agent header.")

case class ResponseHeadersIstioAttribute(val value: Map[String, String]) extends IstioAttribute[Map[String, String]]("response.headers", StringMapAttribute, "HTTP response headers.")

case class ResponseSizeIstioAttribute(val value: Long) extends IstioAttribute[Long]("response.size", Int64Attribute, "Size of the response body in bytes")

case class ResponseTimeIstioAttribute(val value: Long) extends IstioAttribute[Long]("response.time", TimestampAttribute, "The timestamp when the target produced the response.")

case class ResponseDurationIstioAttribute(val value: Duration) extends IstioAttribute[Duration]("response.duration", DurationAttribute, "The amount of time the response took to generate.")

case class ResponseCodeIstioAttribute(val value: Long) extends IstioAttribute[Long]("response.code", Int64Attribute, "The response’s HTTP status code")
