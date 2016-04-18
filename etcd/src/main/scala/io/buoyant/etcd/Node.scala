package io.buoyant.etcd

import com.twitter.finagle.Path
import com.twitter.io.Buf
import com.twitter.util.{Duration, Time}

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
