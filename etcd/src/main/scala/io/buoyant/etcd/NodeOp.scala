package io.buoyant.etcd

import com.twitter.util._

sealed class Action(val name: String) {
  override def toString = name
}

object Action {

  object Create extends Action("create")
  object CompareAndSwap extends Action("compareAndSwap")
  object CompareAndDelete extends Action("compareAndDelete")
  object Delete extends Action("delete")
  object Expire extends Action("expire")
  object Get extends Action("get")
  object Set extends Action("set")
  object Update extends Action("update")
  object Watch extends Action("watch")

  val All: Seq[Action] = Seq(
    Create,
    CompareAndSwap,
    CompareAndDelete,
    Delete,
    Expire,
    Get,
    Set,
    Update,
    Watch
  )

  val ByName: Map[String, Action] =
    All.map(a => a.name -> a).toMap

  case class Invalid(name: String)
    extends Exception(s"Invalid action: $name")

  def apply(name: String): Try[Action] =
    ByName.get(name).map(Return(_)) getOrElse Throw(Invalid(name))
}

case class EtcdState(
  index: Long,
  clusterId: String = ""
)

case class NodeOp(
  action: Action,
  node: Node,
  etcd: EtcdState,
  prevNode: Option[Node] = None
)
