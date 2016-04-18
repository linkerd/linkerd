package io.buoyant.etcd

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.conversions.time._
import com.twitter.finagle.{Path, Service}
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.collection.JavaConverters._

/*
 * TODO: use residual path on client to set key prefix.
 */

/*
 * Wire-format representations of NodeOp and Node.
 */

case class Version(etcdserver: String, etcdcluster: String)

private[etcd] case class NodeRsp(
  key: String,
  modifiedIndex: Long,
  createdIndex: Long,
  dir: Boolean = false,
  value: Option[String] = None,
  nodes: Option[Seq[NodeRsp]] = None,
  expiration: Option[String] = None,
  ttl: Option[Int] = None
) {

  def toLease: Try[Option[Lease]] =
    for {
      expiration <- Try {
        expiration map { e =>
          val ms = ISODateTimeFormat.dateTime.parseDateTime(e).getMillis
          Time.fromMilliseconds(ms)
        }
      }
      ttl <- Try(ttl map (_.toInt.seconds))
    } yield for {
      e <- expiration
      t <- ttl
    } yield Lease(e, t)

  def toNode: Try[Node] = {
    val k = if (key == null || key == "") "/" else key
    Try(Path.read(key)) flatMap { key =>
      toLease flatMap { lease =>
        if (dir) {
          val init = Dir(key, modifiedIndex, createdIndex, lease)
          nodes.getOrElse(Seq.empty).foldLeft[Try[Dir]](Return(init)) {
            case (e@Throw(_), _) => e
            case (Return(tree), n) => n.toNode map (tree :+ _)
          }
        } else {
          val buf = value.map(Buf.Utf8(_)) getOrElse Buf.Empty
          Return(Data(key, modifiedIndex, createdIndex, lease, buf))
        }
      }
    }
  }

}

private[etcd] object NodeRsp {

  private[this] def toIsoDate(t: Time): String =
    ISODateTimeFormat.dateTime.print(t.inMillis)

  def apply(node: Node): NodeRsp = node match {
    case Data(key, modified, created, lease, Buf.Utf8(value)) =>
      NodeRsp(key.show, modified, created, false, Some(value), None,
        lease.map { l => toIsoDate(l.expiration) },
        lease.map(_.ttl.inSeconds))

    case Dir(key, modified, created, lease, nodes) =>
      NodeRsp(key.show, modified, created, true, None, Some(nodes.map(NodeRsp(_))),
        lease.map { l => toIsoDate(l.expiration) },
        lease.map(_.ttl.inSeconds))

  }
}

private[etcd] case class NodeOpRsp(
  action: String,
  node: Option[NodeRsp] = None,
  prevNode: Option[NodeRsp] = None
) {

  def toNodeOp(etcd: EtcdState): Try[NodeOp] =
    Action(action) flatMap { action =>
      node match {
        case None =>
          Throw(new Exception("node not specified"))

        case Some(node) =>
          node.toNode match {
            case Throw(e) => Throw(e)

            case Return(node) =>
              val prev = prevNode match {
                case None => Return(None)
                case Some(prev) => prev.toNode map (Some(_))
              }
              prev map (NodeOp(action, node, etcd, _))
          }
      }
    }
}

private[etcd] object NodeOpRsp {

  def apply(op: NodeOp): NodeOpRsp =
    NodeOpRsp(op.action.name, Some(NodeRsp(op.node)), op.prevNode.map(NodeRsp(_)))
}

/*
 * Errors
 */

case class BackoffsExhausted(key: Path, throwable: Throwable)
  extends Exception(key.show, throwable)

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
    val index = msg.headerMap.get(Headers.EtcdIndex).
      flatMap { i => Try(i.toLong).toOption }.
      getOrElse(0L)

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

    client(req) flatMap { rsp =>
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
