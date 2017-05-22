package io.buoyant.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.`type`.TypeReference
import com.twitter.finagle.{Failure, http}
import com.twitter.finagle.http.MediaType
import com.twitter.io.{Buf, Reader}
import com.twitter.util._

/**
 * Contains various classes and methods useful for interacting with the Kubernetes API.
 */
object Api {
  val BufSize = 8 * 1024

  private[k8s] class Response(rsp: http.Response) extends Throwable({
    val content = if (rsp.contentString.isEmpty) "(no content)" else rsp.contentString
    s"""$rsp: $content"""
  })

  val Closed = Failure("k8s observation released", Failure.Interrupted)
  case class UnexpectedResponse(rsp: http.Response) extends Response(rsp)

  /**
   * Represents an HTTP 409 Conflict response, returned by the k8s API when attempting to update a
   * resource with an out-of-date resource version or when attempting to create a resource at an
   * existing name.
   */
  case class Conflict(rsp: http.Response) extends Response(rsp)

  /**
   * Represents an HTTP 404 Not Found response.
   */
  case class NotFound(rsp: http.Response) extends Response(rsp)

  private[k8s] def mkreq(
    method: http.Method,
    path: String,
    content: Option[Buf],
    optParams: (String, Option[String])*
  ): http.Request = {
    val params = optParams collect { case (k, Some(v)) => (k, v) }
    val req = http.Request(path, params: _*)
    req.method = method
    req.contentType = MediaType.Json
    content.foreach(req.content = _)
    req
  }

  private[k8s] def dechunk(reader: Reader, init: Buf = Buf.Empty): Future[Buf] =
    reader.read(BufSize).flatMap {
      case Some(chunk) =>
        dechunk(reader, init.concat(chunk))
      case None =>
        Future.value(init)
    }

  private[k8s] def getContent(msg: http.Message): Future[Buf] =
    msg.headerMap.get("transfer-encoding") match {
      case Some("chunked") => dechunk(msg.reader)
      case _ => Future.value(msg.content)
    }

  private[k8s] def parse[T: TypeReference](rsp: http.Response): Future[T] =
    rsp.status match {
      case http.Status.Successful(_) =>
        getContent(rsp).flatMap { content =>
          Future.const(Json.read[T](content))
        }
      case http.Status.NotFound => Future.exception(Api.NotFound(rsp))
      case http.Status.Conflict => Future.exception(Api.Conflict(rsp))
      case _ => Future.exception(Api.UnexpectedResponse(rsp))
    }
}

/**
 * Generally required as an implicit for list resources. Provides the kubernetes-designated
 * name for the resource, as well as a means of transforming an individual instance into a
 * type-specialized [[Watch]].
 */
trait ObjectDescriptor[O <: KubeObject, W <: Watch[O]] {
  /**
   * @return the URI path segment used for serving lists of resources of type O, e.g. "endpoints",
   *         "configmaps".
   */
  def listName: String

  /**
   *
   * @param o a current object instance
   * @return a Watch (usually Watch.Modified) wrapping `o`
   */
  def toWatch(o: O): W
}

/**
 * Describes an Object in the Kubernetes API (i.e.
 * http://kubernetes.io/docs/api-reference/v1/definitions/#_v1_endpoints)
 */
trait KubeObject {
  def apiVersion: Option[String]
  def metadata: Option[ObjectMeta]
  def kind: Option[String]
}

/**
 * Describes a List of Objects in the Kubernetes API (i.e.
 * [[http://kubernetes.io/docs/api-reference/v1/definitions/#_v1_endpointslist EndpointsList]])
 *
 * @tparam O the type of object contained in the list
 */
trait KubeList[O <: KubeObject] {
  def items: Seq[O]
  def apiVersion: Option[String]
  def metadata: Option[ObjectMeta]
  def kind: Option[String]
}

/**
 * See https://github.com/kubernetes/kubernetes/blob/release-1.2/docs/devel/api-conventions.md#metadata
 * for descriptions of the meanings of the contained fields.
 */
case class ObjectMeta(
  name: Option[String] = None,
  generateName: Option[String] = None,
  namespace: Option[String] = None,
  selfLink: Option[String] = None,
  uid: Option[String] = None,
  resourceVersion: Option[String] = None,
  generation: Option[String] = None,
  creationTimestamp: Option[String] = None,
  deletionTimestamp: Option[String] = None,
  labels: Option[Map[String, String]] = None,
  annotations: Option[Map[String, String]] = None
)

case class ObjectReference(
  kind: Option[String] = None,
  namespace: Option[String] = None,
  name: Option[String] = None,
  uid: Option[String] = None,
  apiVersion: Option[String] = None,
  resourceVersion: Option[String] = None,
  fieldPath: Option[String] = None
)

/**
 * An event resulting from a "watch" on the Kubernetes API:
 * http://kubernetes.io/docs/api-reference/v1/definitions/#_json_watchevent
 *
 * Note: Dealing with this class is a little clunky because we haven't been able to get Jackson to
 * handle the combination of generics and polymorphic inheritance correctly. Thus, you'll need to
 * create actual subclasses (i.e. FooWatch extends Watch[Foo]) rather than using Watch[Foo]
 * directly, and set the correct Jackson annotations on those, to ensure correct parsing.
 */
trait Watch[O <: KubeObject] {
  def resourceVersion: Option[String]
}

object Watch {
  trait WithObject[O <: KubeObject] extends Watch[O] {
    def `object`: O
    @JsonIgnore
    def resourceVersion = `object`.metadata.flatMap(_.resourceVersion)
  }
  trait Added[O <: KubeObject] extends WithObject[O]
  trait Modified[O <: KubeObject] extends WithObject[O]
  trait Deleted[O <: KubeObject] extends WithObject[O]
  trait Error[O <: KubeObject] extends Watch[O] {
    def status: Status
    @JsonIgnore
    def resourceVersion = None
  }
}

case class Status(
  kind: Option[String] = None,
  apiVersion: Option[String] = None,
  metadata: Option[ObjectMeta] = None,
  status: Option[String] = None,
  message: Option[String] = None,
  reason: Option[String] = None,
  details: Option[StatusDetails] = None,
  code: Option[Int] = None
)

case class StatusDetails(
  name: Option[String] = None,
  kind: Option[String] = None,
  causes: Option[Seq[StatusCause]] = None,
  retryAfterSeconds: Option[Int] = None
)

case class StatusCause(
  reason: Option[String] = None,
  message: Option[String] = None,
  field: Option[String] = None
)

