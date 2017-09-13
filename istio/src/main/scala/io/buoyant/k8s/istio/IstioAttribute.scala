package io.buoyant.k8s.istio

import com.twitter.util.Duration

/**
 * From the [https://istio.io/docs/concepts/policy-and-control/attributes.html Istio documentation]
 *
 * > Istio uses attributes to control the runtime behavior of services running in the service mesh. Attributes are named and
 * > typed pieces of metadata describing ingress and egress traffic and the environment this traffic occurs in. An Istio attribute
 * > carries a specific piece of information such as the error code of an API request, the latency of an API request, or the original
 * > IP address of a TCP connection
 *
 * @param name
 * @param description
 * @tparam T
 */
sealed abstract class IstioAttribute[T](val name: String, val description: String) {
  val value: T
}

sealed abstract class StringAttribute(
  name: String,
  description: String
) extends IstioAttribute[String](name, description)

sealed abstract class Int64Attribute(
  name: String,
  description: String
) extends IstioAttribute[Long](name, description)

sealed abstract class StringMapAttribute(
  name: String,
  description: String
) extends IstioAttribute[Map[String, String]](name, description)

sealed abstract class IpAddressAttribute(
  name: String,
  description: String
) extends IstioAttribute[String](name, description)

sealed abstract class TimestampAttribute(
  name: String,
  description: String
) extends Int64Attribute(name, description)

sealed abstract class DurationAttribute(
  name: String,
  description: String
) extends IstioAttribute[Duration](name, description)

case class SourceIpIstioAttribute(val value: String)
  extends StringAttribute(
    "source.ip",
    "Client IP address. e.g.10.0.0.117"
  )

case class SourceServiceIstioAttribute(value: String)
  extends StringAttribute(
    "source.service",
    "The fully qualified name of the service that the client belongs to. e.g.redis-master.my-namespace.svc.cluster.local"
  )

case class SourceNameIstioAttribute(value: String)
  extends StringAttribute(
    "source.name",
    "The short name part of the source service. e.g.redis-master"
  )

case class SourceNamespaceIstioAttribute(value: String)
  extends StringAttribute(
    "source.namespace",
    "The namespace part of the source service. e.g.my-namespace"
  )

case class SourceDomainIstioAttribute(value: String)
  extends StringAttribute(
    "source.domain",
    "The domain suffix part of the source service, excluding the name and the namespace. e.g.svc.cluster.local"
  )

case class SourceUidIstioAttribute(value: String)
  extends StringAttribute(
    "source.uid",
    "Platform-specific unique identifier for the client instance of the source service. e.g.kubernetes://redis-master-2353460263-1ecey.my-namespace"
  )

case class SourceLabelIstioAttribute(value: Map[String, String])
  extends StringMapAttribute(
    "source.labels",
    "A map of key-value pairs attached to the client instance. e.g.version => v1"
  )

case class SourceUserIstioAttribute(value: String)
  extends StringAttribute(
    "source.user",
    "The identity of the immediate sender of the request, authenticated by mTLS. e.g.service-account-foo"
  )

case class TargetIpIstioAttribute(value: String)
  extends StringAttribute(
    "target.ip",
    "Server IP address. e.g.10.0.0.104"
  )

case class TargetPortIstioAttribute(value: Long)
  extends Int64Attribute(
    "target.port",
    "The recipient port on the server IP address. e.g.8080"
  )

case class TargetServiceIstioAttribute(value: String)
  extends StringAttribute(
    "target.service",
    "The fully qualified name of the service that the server belongs to. e.g.my-svc.my-namespace.svc.cluster.local"
  )

case class TargetNameIstioAttribute(value: String)
  extends StringAttribute(
    "target.name",
    "The short name part of the target service. e.g.my-svc"
  )

case class TargetNamespaceIstioAttribute(value: String)
  extends StringAttribute(
    "target.namespace",
    "The namespace part of the target service. e.g.my-namespace"
  )

case class TargetDomainIstioAttribute(value: String)
  extends StringAttribute(
    "target.domain",
    "The domain suffix part of the target service, excluding the name and the namespace. e.g.svc.cluster.local)"
  )

case class TargetUidIstioAttribute(value: String)
  extends StringAttribute(
    "target.uid",
    "Platform-specific unique identifier for the server instance of the target service. e.g.kubernetes://my-svc-234443-5sffe.my-namespace"
  )

case class TargetLabelsIstioAttribute(value: Map[String, String])
  extends StringMapAttribute(
    "target.labels",
    "A map of key-value pairs attached to the server instance. e.g.version => v2"
  )

case class TargetUserIstioAttribute(value: String)
  extends StringAttribute(
    "target.user",
    "The user running the target application. e.g.service-account"
  )

case class RequestHeadersIstioAttribute(value: Map[String, String])
  extends StringMapAttribute(
    "request.headers",
    "HTTP request headers."
  )

case class RequestIdIstioAttribute(value: String)
  extends StringAttribute(
    "request.id",
    "An ID for the request with statistically low probability of collision."
  )

case class RequestPathIstioAttribute(value: String)
  extends StringAttribute(
    "request.path",
    "The HTTP URL path including query string"
  )

case class RequestHostIstioAttribute(value: String)
  extends StringAttribute(
    "request.host",
    "HTTP/1.x host header or HTTP/2 authority header. e.g.redis-master:3337"
  )

case class RequestMethodIstioAttribute(value: String)
  extends StringAttribute(
    "request.method",
    "The HTTP method."
  )

case class RequestReasonIstioAttribute(value: String)
  extends StringAttribute(
    "request.reason",
    "The request reason used by auditing systems."
  )

case class RequestRefererIstioAttribute(value: String)
  extends StringAttribute(
    "request.referer",
    "The HTTP referer header."
  )

case class RequestSchemeIstioAttribute(value: String)
  extends StringAttribute(
    "request.scheme",
    "URI Scheme of the request"
  )

case class RequestSizeIstioAttribute(value: Long)
  extends Int64Attribute(
    "request.size",
    "Size of the request in bytes. For HTTP requests this is equivalent to the Content-Length header."
  )

case class RequestTimeIstioAttribute(value: Long)
  extends Int64Attribute(
    "request.time",
    "The timestamp when the target receives the request. This should be equivalent to Firebase now."
  )

case class RequestUseragentIstioAttribute(value: String)
  extends StringAttribute(
    "request.useragent",
    "The HTTP User-Agent header."
  )

case class ResponseHeadersIstioAttribute(value: Map[String, String])
  extends StringMapAttribute(
    "response.headers",
    "HTTP response headers."
  )

case class ResponseSizeIstioAttribute(value: Long)
  extends Int64Attribute(
    "response.size",
    "Size of the response body in bytes"
  )

case class ResponseTimeIstioAttribute(value: Long)
  extends Int64Attribute(
    "response.time",
    "The timestamp when the target produced the response."
  )

case class ResponseDurationIstioAttribute(value: Duration)
  extends DurationAttribute(
    "response.duration",
    "The amount of time the response took to generate."
  )

case class ResponseCodeIstioAttribute(value: Long)
  extends Int64Attribute(
    "response.code",
    "The response’s HTTP status code"
  )
