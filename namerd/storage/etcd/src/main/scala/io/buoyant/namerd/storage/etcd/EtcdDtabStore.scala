package io.buoyant.namerd
package storage.etcd

import com.twitter.finagle.{Dtab, Path}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.etcd.{Node, NodeOp, ApiError, Key}
import io.buoyant.namerd.DtabStore.DtabNamespaceDoesNotExistException

class EtcdDtabStore(root: Key) extends DtabStore {
  import DtabStore.{DtabVersionMismatchException, Version}

  override def list(): Activity[Set[Ns]] = {
    val run = Var.async[Activity.State[Set[Ns]]](Activity.Pending) { updates =>

      @volatile var state: Activity.State[Set[Ns]] = Activity.Pending

      def namespace(nodeKey: Path): Ns = nodeKey.drop(root.path.size).show

      root.events(recursive = true).respond {
        case Return(nodeOp) =>
          nodeOp.action match {
            case NodeOp.Action.Get =>
              nodeOp.node match {
                case Node.Dir(_, _, _, _, nodes) =>
                  val namespaces = nodes.map(_.key).map(namespace).toSet
                  state = Activity.Ok(namespaces)
                case data: Node.Data =>
                  state = Activity.Failed(new IllegalStateException(s"Dtab root ${root.path.show} is not a directory"))
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

  def update(ns: Ns, dtab: Dtab, version: Version): Future[Unit] = {
    val Buf.Utf8(vstr) = version
    val buf = Buf.Utf8(dtab.show)
    val key = root.key(Path.Utf8(ns))
    Future(vstr.toLong).rescue {
      case NumberFormatException =>
        Future.exception(new DtabVersionMismatchException)
    }.flatMap { index =>
      key.compareAndSwap(buf, prevIndex = Some(index))
    }.rescue {
      case ApiError(ApiError.TestFailed, _, _, _) =>
        Future.exception(new DtabVersionMismatchException)
      case ApiError(ApiError.KeyNotFound, _, _, _) =>
        Future.exception(new DtabNamespaceDoesNotExistException(ns))
    }.unit
  }

  def put(ns: Ns, dtab: Dtab): Future[Unit] =
    root.key(Path.Utf8(ns)).set(Some(Buf.Utf8(dtab.show))).unit

  def observe(ns: Ns): Activity[Option[VersionedDtab]] = ???
}
