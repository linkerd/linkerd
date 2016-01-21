package io.buoyant.consul

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.{Service, http}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._

case class UnexpectedResponse(rsp: http.Response) extends Throwable

/**
 * A partial implementation of the Consul V1 API.
 */
package object v1 {

  type Client = Service[http.Request, http.Response]
  val versionString = "v1"

  private[consul] val log = Logger.get("consul")

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

  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private[this] def readJson[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  object Api {
    def apply(c: Client): CatalogApi = new CatalogApi(c, s"/$versionString")
  }

  class CatalogApi(client: Client, uriPrefix: String) extends Closable {
    def close(deadline: Time) = client.close(deadline)
    val catalogPrefix = s"$uriPrefix/catalog"

    // https://www.consul.io/docs/agent/http/catalog.html#catalog_datacenters
    def datacenters: Future[Seq[String]] = {
      val req = mkreq(http.Method.Get, s"$catalogPrefix/datacenters")
      client(req).flatMap {
        case rsp if rsp.status == http.Status.Ok =>
          Future.const(readJson[Seq[String]](rsp.content))
        case rsp => Future.exception(UnexpectedResponse(rsp))
      }
    }

    // https://www.consul.io/docs/agent/http/catalog.html#catalog_services
    def serviceMap(
      datacenter: Option[String] = None,
      blockingIndex: Option[String] = None
    ): Future[Indexed[Map[String, Seq[String]]]] = {
      val req = mkreq(
        http.Method.Get,
        s"$catalogPrefix/services",
        "index" -> blockingIndex,
        "dc" -> datacenter
      )
      client(req).flatMap { rsp => Future.const(Indexed.mk[Map[String, Seq[String]]](rsp)) }
    }

    // https://www.consul.io/docs/agent/http/catalog.html#catalog_service
    def serviceNodes(
      serviceName: String,
      datacenter: Option[String] = None,
      blockingIndex: Option[String] = None
    ): Future[Indexed[Seq[ServiceNode]]] = {
      val req = mkreq(
        http.Method.Get,
        s"$catalogPrefix/service/$serviceName",
        "index" -> blockingIndex,
        "dc" -> datacenter
      )
      client(req).flatMap { rsp => Future.const(Indexed.mk[Seq[ServiceNode]](rsp)) }
    }
  }

  object Headers {
    val Index = "X-Consul-Index"
  }

  case class Indexed[T](value: T, index: Option[String])
  object Indexed {
    def mk[T: Manifest](rsp: http.Response): Try[Indexed[T]] = rsp.status match {
      case http.Status.Ok =>
        readJson[T](rsp.content).map { t =>
          Indexed[T](t, rsp.headerMap.get(Headers.Index))
        }
      case status => throw UnexpectedResponse(rsp)
    }
  }

  case class ServiceNode(
    Node: Option[String],
    Address: Option[String],
    ServiceID: Option[String],
    ServiceName: Option[String],
    ServiceTags: Option[Seq[String]],
    ServiceAddress: Option[String],
    ServicePort: Option[Int]
  )
}
