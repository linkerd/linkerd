package io.buoyant.etcd

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.{Path, Service}
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util._
import scala.collection.JavaConverters._

case class Version(etcdserver: String, etcdcluster: String)

case class UnexpectedResponse(
  method: Method,
  uri: String,
  params: Seq[(String, String)],
  status: Status,
  state: EtcdState
) extends Exception({
  val ps = params.map { case (k, v) => s"$k -> $v" }.mkString("(", "), (", ")")
  s"""$method $uri [$ps] $status"""
})

object Etcd {

  private[etcd] val keysPrefixPath = Path.Utf8("v2", "keys")
  private[etcd] val versionPath = Path.Utf8("version")

  private[etcd] def mkReq(
    path: Path,
    method: Method = Method.Get,
    params: Seq[(String, String)] = Nil
  ): Request = {
    method match {
      case Method.Post | Method.Put =>
        val req = Request(method, path.show)
        req.contentType = MediaType.WwwForm
        EtcdFormParams.set(req, params)
        req

      case _ =>
        val req = Request(path.show, params: _*)
        req.method = method
        req
    }
  }

  private[etcd] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  def readJson[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteArray.Owned(bytes, begin, end) = Buf.ByteArray.coerce(buf)
    Try(mapper.readValue[T](bytes, begin, end - begin))
  }

  private[etcd] def toNodeOp(
    req: Request,
    rsp: Response,
    key: Path,
    params: Seq[(String, String)] = Seq.empty
  ): Future[NodeOp] = {
    val state = getEtcdState(rsp)
    (req.method, rsp.status) match {

      case (Method.Get | Method.Head | Method.Delete, Status.Ok) =>
        val op = readJson[NodeOpRsp](rsp.content).flatMap(_.toNodeOp(state))
        Future.const(op)

      case (Method.Put | Method.Post, Status.Created) =>
        val op = readJson[NodeOpRsp](rsp.content).flatMap(_.toNodeOp(state))
        Future.const(op)

      case (method, status) =>
        val e = readJson[ApiError](rsp.content) match {
          case Return(apiError) => apiError
          case Throw(e) => UnexpectedResponse(method, req.uri, params, status, state)
        }
        Future.exception(e)
    }
  }

  private[etcd] object Headers {
    val ClusterId = "x-etcd-cluster-id"
    val EtcdIndex = "x-etcd-index"
  }

  private[etcd] def getEtcdState(msg: Message): EtcdState = {
    val index = msg.headerMap.get(Headers.EtcdIndex)
      .flatMap { i => Try(i.toLong).toOption }
      .getOrElse(0L)

    val id = msg.headerMap.getOrElse(Headers.ClusterId, "")

    EtcdState(index, id)
  }
}

/**
 * An etcd client.
 *
 * The client service is responsible for setting the Host: header, etc...
 */
class Etcd(client: Service[Request, Response]) extends Closable {

  import Etcd._

  /** Fetch etcd server version info */
  def version(): Future[Version] = {
    val req = mkReq(versionPath)
    req.headerMap("accept") = MediaType.Json

    client(req).flatMap { rsp =>
      rsp.status match {
        case Status.Ok =>
          Future.const(readJson[Version](rsp.content))

        case status =>
          Future.exception(UnexpectedResponse(req.method, req.uri, Nil, status, EtcdState(0)))
      }
    }
  }

  def key(k: Path): Key = new Key(k, client)
  def key(k: String): Key = key(Path.read(k))

  def close(deadline: Time) = client.close()
}
