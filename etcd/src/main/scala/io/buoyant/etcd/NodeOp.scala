package io.buoyant.etcd

import com.twitter.finagle.Path
import com.twitter.finagle.http._
import com.twitter.util._

case class NodeOp(
  action: NodeOp.Action,
  node: Node,
  etcd: Etcd.State,
  prevNode: Option[Node] = None
)

object NodeOp {

  sealed abstract class Action(val name: String) {
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

    def mk(name: String): Try[Action] =
      Try.orThrow(ByName.get(name)) { () => Invalid(name) }
  }

  private[etcd] def mk(
    req: Request,
    rsp: Response,
    key: Path,
    params: Seq[(String, String)] = Seq.empty
  ): Try[NodeOp] = {
    val state = Etcd.State.mk(rsp)
    (req.method, rsp.status) match {
      case (Method.Get | Method.Head | Method.Delete, Status.Ok) =>
        Etcd.readJson[Json](rsp.content).flatMap(_.toNodeOp(state))

      case (Method.Put | Method.Post, Status.Created | Status.Ok) =>
        Etcd.readJson[Json](rsp.content).flatMap(_.toNodeOp(state))

      case (method, status) =>
        Etcd.readJson[ApiError](rsp.content).transform {
          case Return(ae) => Throw(ae)
          case Throw(_) => Throw(UnexpectedResponse(method, req.uri, params, status, state))
        }
    }
  }

  /**
   * Representation of a Node operation, as returned from etcd.
   */
  private[etcd] case class Json(
    action: String,
    node: Option[Node.Json] = None,
    prevNode: Option[Node.Json] = None
  ) {

    def toNodeOp(etcd: Etcd.State): Try[NodeOp] =
      Action.mk(action).flatMap { action =>
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

  private[etcd] object Json {
    def apply(op: NodeOp): Json =
      Json(op.action.name, Some(Node.Json(op.node)), op.prevNode.map(Node.Json(_)))
  }

}
