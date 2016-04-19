package io.buoyant.etcd

import com.twitter.conversions.time._
import com.twitter.finagle.Path
import com.twitter.io.Buf
import com.twitter.util.{Duration, Return, Throw, Time, Try}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

sealed trait Node {
  def key: Path
  def modifiedIndex: Long
  def createdIndex: Long
  def lease: Option[Lease]
}

case class Lease(expiration: Time, ttl: Duration)

case class Dir(
  key: Path,
  modifiedIndex: Long,
  createdIndex: Long,
  lease: Option[Lease] = None,
  nodes: Seq[Node] = Seq.empty
) extends Node {

  def :+(node: Node): Dir =
    copy(nodes = nodes :+ node)

}

case class Data(
  key: Path,
  modifiedIndex: Long,
  createdIndex: Long,
  lease: Option[Lease] = None,
  value: Buf = Buf.Empty
) extends Node

/**
 * Wire representation
 */
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
        expiration.map { e =>
          val ms = ISODateTimeFormat.dateTime.parseDateTime(e).getMillis
          Time.fromMilliseconds(ms)
        }
      }
      ttl <- Try(ttl.map(_.toInt.seconds))
    } yield for {
      e <- expiration
      t <- ttl
    } yield Lease(e, t)

  def toNode: Try[Node] = {
    val k = if (key == null || key == "") "/" else key
    Try(Path.read(key)).flatMap { key =>
      toLease.flatMap { lease =>
        if (dir) {
          val init = Dir(key, modifiedIndex, createdIndex, lease)
          nodes.getOrElse(Seq.empty).foldLeft[Try[Dir]](Return(init)) {
            case (e@Throw(_), _) => e
            case (Return(tree), n) => n.toNode.map(tree :+ _)
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
