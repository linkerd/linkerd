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

/**
 * Wire representation
 */
private[etcd] case class NodeOpRsp(
  action: String,
  node: Option[NodeRsp] = None,
  prevNode: Option[NodeRsp] = None
) {

  def toNodeOp(etcd: EtcdState): Try[NodeOp] =
    Action(action).flatMap { action =>
      node match {
        case None =>
          Throw(new Exception("node not specified"))

        case Some(node) =>
          node.toNode match {
            case Throw(e) => Throw(e)

            case Return(node) =>
              val prev = prevNode match {
                case None => Return(None)
                case Some(prev) => prev.toNode.map(Some(_))
              }
              prev.map(NodeOp(action, node, etcd, _))
          }
      }
    }
}

private[etcd] object NodeOpRsp {

  def apply(op: NodeOp): NodeOpRsp =
    NodeOpRsp(op.action.name, Some(NodeRsp(op.node)), op.prevNode.map(NodeRsp(_)))
}
