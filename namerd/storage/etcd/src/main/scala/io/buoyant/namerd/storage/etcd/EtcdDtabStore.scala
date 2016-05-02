package io.buoyant.namerd
package storage.etcd

import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.{Activity, Future, _}
import io.buoyant.etcd.{Key, Node, NodeOp}

class EtcdDtabStore(key: Key) extends DtabStore {
  import DtabStore.Version

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
              state match {
                case Activity.Ok(namespaces) =>
                  state = Activity.Ok(namespaces + namespace(nodeOp.node.key))
                case _ =>
              }

            case NodeOp.Action.Delete =>
              state match {
                case Activity.Ok(namespaces) =>
                  state = Activity.Ok(namespaces - namespace(nodeOp.node.key))
                case _ =>
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

  def create(ns: Ns, dtab: Dtab): Future[Unit] = ???

  def delete(ns: Ns): Future[Unit] = ???

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = ???

  def put(ns: Ns, dtab: Dtab): Future[Unit] = ???

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = ???
}
