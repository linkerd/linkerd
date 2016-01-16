package io.buoyant.k8s

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.concurrent.AsyncStream
import com.twitter.conversions.time._
import com.twitter.finagle.{Filter, Service, http}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service.{Backoff, RetryBudget, RetryFilter, RetryPolicy}
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.io.{Buf, Reader}
import com.twitter.logging.Logger
import com.twitter.util._
import java.util.concurrent.atomic.AtomicReference

object Closed extends Throwable
case class UnexpectedResponse(rsp: http.Response) extends Throwable

/**
 * A partial implementation of the Kubernetes V1 API.
 */
package object v1 {

  type Client = Service[http.Request, http.Response]

  val versionString = "v1"
  val BufSize = 8 * 1024

  private[k8s] val log = Logger.get("k8s")

  object Api {
    def apply(c: Client): Api = new Api(c, s"/api/$versionString")
  }

  private[this] def mkreq(
    method: http.Method,
    path: String,
    optParams: (String, Option[String])*
  ): http.Request = {
    val params = optParams collect { case (k, Some(v)) => (k, v) }
    val req = http.Request(path, params: _*)
    req.method = method
    req
  }

  private[this] def dechunk(reader: Reader, init: Buf = Buf.Empty): Future[Buf] =
    reader.read(BufSize) flatMap {
      case Some(chunk) =>
        dechunk(reader, init.concat(chunk))
      case None =>
        Future.value(init)
    }

  private[this] def getContent(msg: http.Message): Future[Buf] =
    msg.headerMap.get("transfer-encoding") match {
      case Some("chunked") => dechunk(msg.reader)
      case _ => Future.value(msg.content)
    }

  private[this] def parse[T: Manifest](rsp: http.Response): Future[T] =
    getContent(rsp) flatMap { content =>
      Future.const(Json.read[T](content))
    }

  class Api(client: Client, uriPrefix: String) extends Closable {
    def close(deadline: Time) = client.close(deadline)
    def namespace(ns: String): NsApi = new NsApi(client, uriPrefix, ns)
    val endpoints: EndpointsApi = new EndpointsApi(client, uriPrefix)
  }

  class NsApi(client: Client, baseUriPrefix: String, ns: String) extends Closable {
    def close(deadline: Time) = client.close(deadline)
    def clearNamespace: Api = new Api(client, baseUriPrefix)

    val endpoints: NsEndpointsApi =
      new NsEndpointsApi(client, s"$baseUriPrefix/namespaces/$ns")
  }

  class EndpointsApi(
    client: Client,
    uriPrefix: String,
    backoffs: Stream[Duration] = Backoff.exponentialJittered(1.milliseconds, 5.seconds),
    stats: StatsReceiver = DefaultStatsReceiver
  ) extends Closable {
    def close(deadline: Time) = client.close(deadline)

    private[this] val infiniteRetryFilter = new RetryFilter[http.Request, http.Response](
      RetryPolicy.backoff(backoffs) {
        // We will assume 5xx are retryable, everything else is not for now
        case (_, Return(rep)) => rep.status.code >= 500 && rep.status.code < 600
        case (_, Throw(NonFatal(ex))) =>
          log.error(s"retrying k8s endpoints request on error $ex")
          true
      },
      HighResTimer.Default,
      stats,
      RetryBudget.Infinite
    )

    def apply(
      labelSelector: Option[String] = None,
      fieldSelector: Option[String] = None,
      resourceVersion: Option[String] = None,
      retryIndefinitely: Boolean = false
    ): Future[EndpointsList] = {
      val req = mkreq(http.Method.Get, s"$uriPrefix/endpoints",
        "labelSelector" -> labelSelector,
        "fieldSelector" -> fieldSelector,
        "resourceVersion" -> resourceVersion)
      val retry = if (retryIndefinitely) infiniteRetryFilter else Filter.identity[http.Request, http.Response]
      val retryingClient = retry andThen client
      retryingClient(req).flatMap(parse[EndpointsList])
    }

    /**
     * Watch the namespace API for changes, using a chunked HTTP request.
     *
     * TODO: investigate k8s websockets support; might or might not be cleaner
     */
    def watch(
      labelSelector: Option[String] = None,
      fieldSelector: Option[String] = None,
      resourceVersion: Option[String] = None
    ): (AsyncStream[EndpointsWatch], Closable) = {
      val close = new AtomicReference[Closable](Closable.nop)

      // Internal method used to recursively retry watches as needed on failures.
      def _watch(resourceVersion: Option[String] = None): AsyncStream[EndpointsWatch] = {
        val req = mkreq(http.Method.Get, s"$uriPrefix/endpoints",
          "watch" -> Some("true"),
          "labelSelector" -> labelSelector,
          "fieldSelector" -> fieldSelector,
          "resourceVersion" -> resourceVersion)

        def currentVersion(initial: Option[String], watch: AsyncStream[EndpointsWatch]): Future[Option[String]] = {
          watch.foldLeft(initial) {
            (version: Option[String], watch: v1.EndpointsWatch) =>
              for {
                meta <- watch match {
                  case a: EndpointsWatch.Added => a.endpoints.metadata
                  case m: EndpointsWatch.Modified => m.endpoints.metadata
                  case d: EndpointsWatch.Deleted => d.endpoints.metadata
                  // if the last thing we saw was an Error, we want to start over from a list/watch.
                  // We may discover later that we can be more surgical here.
                  case e: EndpointsWatch.Error => None
                }
                newVersion <- meta.resourceVersion
              } yield newVersion
          }
        }

        def watchFromResponse(rsp: Response): AsyncStream[EndpointsWatch] = rsp.status match {
          // NOTE: 5xx-class statuses will be retried by the infiniteRetryFilter above.
          case http.Status.Ok =>
            close.set(Closable.make { _ =>
              log.debug("k8s watch closed")
              Future {
                rsp.reader.discard()
              } handle {
                case _: Reader.ReaderDiscarded =>
              }
            })
            val theStream = Json.readStream[EndpointsWatch](rsp.reader, BufSize)
            theStream ++
              AsyncStream.fromFuture(
                currentVersion(resourceVersion, theStream) flatMap {
                  case Some(version) => Future.value(_watch(Some(version)))
                  case None =>
                    // In this case, we want to try loading the initial information instead before watching again.
                    apply(labelSelector, fieldSelector, None, true) map { endpoints =>
                      AsyncStream.fromSeq(endpoints.items map EndpointsWatch.Modified) ++
                        _watch(endpoints.metadata.flatMap(_.resourceVersion))
                    }
                }
              ).flatten

          case status =>
            close.set(Closable.nop)
            log.debug("k8s failed to watch endpoints: %d %s", status.code, status.reason)
            val f = Future.exception(UnexpectedResponse(rsp))
            AsyncStream.fromFuture(f)
        }

        val retryingClient = infiniteRetryFilter andThen client

        val rsp = retryingClient(req)
        close.set(Closable.make { _ =>
          log.trace("k8s watch cancelled")
          rsp.raise(Closed)
          Future.Unit
        })

        AsyncStream.fromFuture(rsp).flatMap(watchFromResponse)
      }
      (_watch(resourceVersion), Closable.ref(close))
    }
  }

  class NsEndpointsApi(client: Client, uriPrefix: String)
    extends EndpointsApi(client, uriPrefix) {

    def named(name: String): Future[Endpoints] = {
      val req = mkreq(http.Method.Get, s"$uriPrefix/endpoints/$name")
      client(req) flatMap parse[Endpoints]
    }
  }

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

  case class EndpointsList(
    items: Seq[Endpoints],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  )

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Added], name = "ADDED"),
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Modified], name = "MODIFIED"),
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Deleted], name = "DELETED"),
    new JsonSubTypes.Type(value = classOf[EndpointsWatch.Error], name = "ERROR")
  ))
  sealed trait EndpointsWatch
  object EndpointsWatch {
    case class Added(
      @JsonProperty(value = "object") endpoints: Endpoints
    ) extends EndpointsWatch

    case class Modified(
      @JsonProperty(value = "object") endpoints: Endpoints
    ) extends EndpointsWatch

    case class Deleted(
      @JsonProperty(value = "object") endpoints: Endpoints
    ) extends EndpointsWatch

    case class Error(
      @JsonProperty(value = "object") status: Status
    ) extends EndpointsWatch
  }

  case class Endpoints(
    subsets: Seq[EndpointSubset],
    kind: Option[String] = None,
    metadata: Option[ObjectMeta] = None,
    apiVersion: Option[String] = None
  )

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
