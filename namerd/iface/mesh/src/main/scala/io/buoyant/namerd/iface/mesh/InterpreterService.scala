package io.buoyant.namerd
package iface.mesh

import com.twitter.finagle.{Addr, Dtab, Name, Namer, NameTree, Path}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{GrpcStatus, ServerDispatcher, Stream, VarEventStream}
import io.buoyant.namer.{ConfiguredDtabNamer, Delegator}
import io.linkerd.mesh
import io.linkerd.mesh.Converters._

/**
 * Applies a Dtab to a Path, producing a bound name tree.
 *
 * Lookups are scoped within a `root` path. Currently this path must
 * contain a single element, used as the DtabStore's `namespace.`
 */
object InterpreterService {

  def apply(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ): mesh.Interpreter.Server =
    new mesh.Interpreter.Server(new Impl(store, namers, stats))

  private[this] class Impl(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ) extends mesh.Interpreter {

    override def getBoundTree(req: mesh.BindReq): Future[mesh.BoundTreeRsp] = req match {
      case mesh.BindReq(None, _, _) => Future.exception(Errors.NoRoot)
      case mesh.BindReq(_, None | Some(mesh.Path(Nil)), _) => Future.exception(Errors.NoName)
      case mesh.BindReq(Some(proot), Some(pname), dtab0) =>
        fromPath(proot) match {
          case Path.Utf8(ns) =>
            val dtab = dtab0 match {
              case None => Dtab.empty
              case Some(d) => fromDtab(d)
            }
            val name = fromPath(pname)
            getNs(ns).bind(dtab, name).toFuture.map(toBoundTreeRsp)

          case root => Future.exception(Errors.InvalidRoot(root))
        }
    }

    override def streamBoundTree(req: mesh.BindReq): Stream[mesh.BoundTreeRsp] = req match {
      case mesh.BindReq(None, _, _) => Stream.exception(Errors.NoRoot)
      case mesh.BindReq(_, None | Some(mesh.Path(Nil)), _) => Stream.exception(Errors.NoName)
      case mesh.BindReq(Some(proot), Some(pname), dtab0) =>
        fromPath(proot) match {
          case Path.Utf8(ns) =>
            val name = fromPath(pname)
            val dtab = dtab0 match {
              case None => Dtab.empty
              case Some(d) => fromDtab(d)
            }
            val evs = getNs(ns).bind(dtab, name).values.map(toBoundTreeRspEv)
            VarEventStream(evs)

          case root => Stream.exception(Errors.InvalidRoot(root))
        }
    }

    private[this] def getNs(ns: String): NameInterpreter with Delegator = {
      val dtabVar = store.observe(ns).map(_extractDtab)
      ConfiguredDtabNamer(dtabVar, namers.toSeq)
    }
  }

  private[this] val toBoundWeightedTree: NameTree.Weighted[Name.Bound] => mesh.BoundNameTree.Union.Weighted =
    wt => mesh.BoundNameTree.Union.Weighted(Some(wt.weight), Some(toBoundNameTree(wt.tree)))

  private[this] val BoundTreeNeg = mesh.BoundNameTree.OneofNode.Neg(mesh.BoundNameTree.Neg())
  private[this] val BoundTreeFail = mesh.BoundNameTree.OneofNode.Fail(mesh.BoundNameTree.Fail())
  private[this] val BoundTreeEmpty = mesh.BoundNameTree.OneofNode.Empty(mesh.BoundNameTree.Empty())

  private[this] val toBoundNameTree: NameTree[Name.Bound] => mesh.BoundNameTree = { tree =>
    val ptree = tree match {
      case NameTree.Neg => BoundTreeNeg
      case NameTree.Fail => BoundTreeFail
      case NameTree.Empty => BoundTreeEmpty

      case NameTree.Leaf(name) =>
        name.id match {
          case id: Path =>
            val pid = toPath(id)
            val ppath = toPath(name.path)
            val leaf = mesh.BoundNameTree.Leaf(Some(pid), Some(ppath))
            mesh.BoundNameTree.OneofNode.Leaf(leaf)

          case _ =>
            mesh.BoundNameTree.OneofNode.Neg(mesh.BoundNameTree.Neg())
        }

      case NameTree.Alt(trees@_*) =>
        val ptrees = trees.map(toBoundNameTree)
        mesh.BoundNameTree.OneofNode.Alt(mesh.BoundNameTree.Alt(ptrees))

      case NameTree.Union(trees@_*) =>
        val ptrees = trees.map(toBoundWeightedTree)
        mesh.BoundNameTree.OneofNode.Union(mesh.BoundNameTree.Union(ptrees))
    }
    mesh.BoundNameTree(Some(ptree))
  }

  private[this] val toBoundTreeRsp: NameTree[Name.Bound] => mesh.BoundTreeRsp =
    t => mesh.BoundTreeRsp(Some(toBoundNameTree(t)))

  private[this] val toBoundTreeRspEv: Try[NameTree[Name.Bound]] => VarEventStream.Ev[mesh.BoundTreeRsp] = {
    case Return(tree) => VarEventStream.Val(toBoundTreeRsp(tree))
    case Throw(e) => VarEventStream.End(Throw(e))
  }

  private[this] val _extractDtab: Option[VersionedDtab] => Dtab = {
    case None => Dtab.empty
    case Some(VersionedDtab(dtab, _)) => dtab
  }
}
