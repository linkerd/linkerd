package io.buoyant.namerd
package iface.mesh

import com.twitter.finagle.{Addr, Dentry, Dtab, Name, NameTree, Namer, Path}
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Closable, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream, VarEventStream}
import io.buoyant.namer.{ConfiguredDtabNamer, Delegator, DelegateTree, RichActivity}
import io.linkerd.mesh
import io.linkerd.mesh.Converters._

/**
 * An Interpreter backed by the io.buoyant.proto.namerd.Interpreter service.
 */
object DelegatorService {

  def apply(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ): mesh.Delegator.Server =
    new mesh.Delegator.Server(new Impl(store, namers, stats))

  private[mesh] class Impl(
    store: DtabStore,
    namers: Map[Path, Namer],
    stats: StatsReceiver
  ) extends mesh.Delegator {

    override def getDtab(req: mesh.DtabReq): Future[mesh.DtabRsp] = req match {
      case mesh.DtabReq(None) => Future.exception(Errors.NoRoot)
      case mesh.DtabReq(Some(proot)) =>
        fromPath(proot) match {
          case Path.Utf8(ns) => store.observe(ns).toFuture.transform(_transformDtabRsp)
          case root => Future.exception(Errors.InvalidRoot(root))
        }
    }

    override def streamDtab(req: mesh.DtabReq): Stream[mesh.DtabRsp] = req match {
      case mesh.DtabReq(None) => Stream.exception(Errors.NoRoot)
      case mesh.DtabReq(Some(proot)) =>
        fromPath(proot) match {
          case Path.Utf8(ns) => VarEventStream(store.observe(ns).values.map(toDtabRspEv))
          case root => Stream.exception(Errors.InvalidRoot(root))
        }
    }

    override def getDelegateTree(req: mesh.DelegateTreeReq): Future[mesh.DelegateTreeRsp] = req match {
      case mesh.DelegateTreeReq(None, _, _) => Future.exception(Errors.NoRoot)
      case mesh.DelegateTreeReq(_, None, _) => Future.exception(Errors.NoName)
      case mesh.DelegateTreeReq(Some(proot), Some(ptree), dtab0) =>
        fromPath(proot) match {
          case Path.Utf8(ns) =>
            val tree = fromPathNameTree(ptree).map(Name.Path(_))
            val dtab = dtab0 match {
              case None => Dtab.empty
              case Some(d) => fromDtab(d)
            }
            getNs(ns).delegate(dtab, tree).map(toDelegateTreeRsp)

          case root => Future.exception(Errors.InvalidRoot(root))
        }
    }

    override def streamDelegateTree(req: mesh.DelegateTreeReq): Stream[mesh.DelegateTreeRsp] =
      Stream.fromFuture(getDelegateTree(req))

    private[this] def getNs(ns: String): NameInterpreter with Delegator = {
      val dtabVar = store.observe(ns).map(_extractDtab)
      ConfiguredDtabNamer(dtabVar, namers.toSeq)
    }
  }

  private[mesh] val _extractDtab: Option[VersionedDtab] => Dtab = {
    case None => Dtab.empty
    case Some(VersionedDtab(dtab, _)) => dtab
  }

  private[mesh] val _transformDtabRsp: Try[Option[VersionedDtab]] => Future[mesh.DtabRsp] = {
    case Return(None) => Future.exception(Errors.RootNotFound)
    case Return(Some(vdtab)) => Future.value(toDtabRsp(vdtab))
    case Throw(e) => Future.exception(GrpcStatus.Internal(e.getMessage))
  }

  private[mesh] val toDtabRsp: VersionedDtab => mesh.DtabRsp = { vdtab =>
    val v = mesh.VersionedDtab.Version(Some(vdtab.version))
    val d = toDtab(vdtab.dtab)
    mesh.DtabRsp(Some(mesh.VersionedDtab(Some(v), Some(d))))
  }

  private[mesh] val toDtabRspEv: Try[Option[VersionedDtab]] => VarEventStream.Ev[mesh.DtabRsp] = {
    case Return(Some(vdtab)) => VarEventStream.Val(toDtabRsp(vdtab))
    case Return(None) => VarEventStream.End(Throw(Errors.RootNotFound)) // TODO empty dtab?
    case Throw(e) => VarEventStream.End(Throw(GrpcStatus.Internal(e.getMessage)))
  }

  private[mesh] val toDelegateWeightedTree: DelegateTree.Weighted[Name.Bound] => mesh.BoundDelegateTree.Union.Weighted =
    wt => mesh.BoundDelegateTree.Union.Weighted(Some(wt.weight), Some(toDelegateTree(wt.tree)))

  private[mesh] def mkBoundDelegateTree(
    path: Path,
    dentry: Option[Dentry],
    node: mesh.BoundDelegateTree.OneofNode
  ) = mesh.BoundDelegateTree(
    Some(toPath(path)),
    dentry.map(toDentry),
    Some(node)
  )

  private[mesh] def mkBoundDelegateTree(
    path: Path,
    dentry: Dentry,
    node: mesh.BoundDelegateTree.OneofNode
  ): mesh.BoundDelegateTree =
    mkBoundDelegateTree(path, Some(dentry), node)

  private[mesh] def mkBoundDelegateTreeLeaf(name: Name.Bound): Option[mesh.BoundDelegateTree.Leaf] =
    name.id match {
      case id: Path =>
        val pid = toPath(id)
        val ppath = toPath(name.path)
        Some(mesh.BoundDelegateTree.Leaf(Some(pid), Some(ppath)))
      case _ => None
    }

  private[mesh] val toDelegateTree: DelegateTree[Name.Bound] => mesh.BoundDelegateTree = {
    case DelegateTree.Neg(p, d) =>
      mkBoundDelegateTree(p, d, mesh.BoundDelegateTree.OneofNode.Neg(mesh.BoundDelegateTree.Neg()))
    case DelegateTree.Fail(p, d) =>
      mkBoundDelegateTree(p, d, mesh.BoundDelegateTree.OneofNode.Fail(mesh.BoundDelegateTree.Fail()))
    case DelegateTree.Empty(p, d) =>
      mkBoundDelegateTree(p, d, mesh.BoundDelegateTree.OneofNode.Empty(mesh.BoundDelegateTree.Empty()))

    case DelegateTree.Delegate(p, d, t) =>
      mkBoundDelegateTree(p, d, mesh.BoundDelegateTree.OneofNode.Delegate(toDelegateTree(t)))
    case DelegateTree.Exception(p, d, e) =>
      val msg = if (e.getMessage == null) "No underlying exception message" else e.getMessage
      mkBoundDelegateTree(p, d, mesh.BoundDelegateTree.OneofNode.Exception(s"BoundDelegateTree Exception: $msg"))
    case DelegateTree.Transformation(p, desc, n, t) =>
      val leaf = mkBoundDelegateTreeLeaf(n)
      val ptrans = mesh.BoundDelegateTree.Transformation(Some(desc), leaf, Some(toDelegateTree(t)))
      mkBoundDelegateTree(p, None, mesh.BoundDelegateTree.OneofNode.Transformation(ptrans))

    case DelegateTree.Leaf(p, d, name) =>
      val node = mkBoundDelegateTreeLeaf(name) match {
        case None => mesh.BoundDelegateTree.OneofNode.Neg(mesh.BoundDelegateTree.Neg())
        case Some(leaf) => mesh.BoundDelegateTree.OneofNode.Leaf(leaf)
      }
      mkBoundDelegateTree(p, d, node)

    case DelegateTree.Alt(p, d, trees@_*) =>
      val ptrees = trees.map(toDelegateTree)
      val node = mesh.BoundDelegateTree.OneofNode.Alt(mesh.BoundDelegateTree.Alt(ptrees))
      mkBoundDelegateTree(p, d, node)

    case DelegateTree.Union(p, d, trees@_*) =>
      val ptrees = trees.map(toDelegateWeightedTree)
      val node = mesh.BoundDelegateTree.OneofNode.Union(mesh.BoundDelegateTree.Union(ptrees))
      mkBoundDelegateTree(p, d, node)
  }

  private[mesh] val toDelegateTreeRsp: DelegateTree[Name.Bound] => mesh.DelegateTreeRsp =
    t => mesh.DelegateTreeRsp(Some(toDelegateTree(t)))

  private[mesh] val toDelegateTreeRspEv: Try[DelegateTree[Name.Bound]] => VarEventStream.Ev[mesh.DelegateTreeRsp] = {
    case Return(tree) => VarEventStream.Val(toDelegateTreeRsp(tree))
    case Throw(e) => VarEventStream.End(Throw(e))
  }
}
