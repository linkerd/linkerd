package io.buoyant.namerd.storage.etcd

import com.twitter.finagle.Path
import com.twitter.util._
import io.buoyant.namerd.{Ns, DtabStore}
import io.buoyant.etcd.{Node, NodeOp, Key}

class EtcdDtabStore(key: Key) extends DtabStore {

  /** List all namespaces */
  override def list(): Activity[Set[Ns]] = {
    val run = Var.async[Activity.State[Set[Ns]]](Activity.Pending) { updates =>

      @volatile var state: Activity.State[Set[Ns]] = Activity.Pending

      def namespace(nodeKey: Path): Ns = nodeKey.drop(key.path.size).show

      key.events(recursive = true).respond {
        case Return(nodeOp) =>
          nodeOp.action match {
            case NodeOp.Action.Get =>
              nodeOp.node match {
                case Node.Dir(_, _, _, _, nodes) =>
                  val namespaces = nodes.map(_.key).map(namespace).toSet
                  state = Activity.Ok(namespaces)
                case data: Node.Data =>
                  // ERROR
              }

            case NodeOp.Action.Create =>
              for(Activity.Ok(namespaces: Set[Ns]) <- state) {
                state = Activity.Ok(namespaces + namespace(nodeOp.node.key))
              }

            case NodeOp.Action.Delete =>
              for(Activity.Ok(namespaces: Set[Ns]) <- state) {
                state = Activity.Ok(namespaces - namespace(nodeOp.node.key))
              }

            case action =>
          }
          updates.update(state)
        case Throw(e) =>
          updates.update(Activity.Failed(e))
      }
    }
    Activity(run)
  }
}
