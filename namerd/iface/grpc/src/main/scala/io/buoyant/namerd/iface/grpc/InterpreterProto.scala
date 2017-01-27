package io.buoyant.namerd
package iface.grpc

import com.twitter.finagle.{Addr, Address, Dentry, Dtab, Name, Namer, NameTree, Path}
import com.twitter.io.Buf
import com.twitter.util.{Closable, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{Stream, VarEventStream}
import io.buoyant.namer.{DelegateTree, Metadata}
import io.buoyant.proto.{Dtab => ProtoDtab, Path => ProtoPath, _}
import io.buoyant.proto.namerd.{Addr => ProtoAddr, VersionedDtab => ProtoVersionedDtab, _}
import java.net.{InetAddress, Inet6Address, InetSocketAddress}
import scala.util.control.NoStackTrace

/**
 * Utilities for translating between io.buoyant.proto and
 * com.twitter.finagle types.
 *
 * This is necessary because this particular protobuf service models
 * Finagle primitives.
 */
private[grpc] object InterpreterProto {

  val toProtoParseRsp: Try[Dtab] => Future[ParseRsp] = {
    case Return(dtab) =>
      val result = ParseRsp.OneofResult.Dtab(toProtoDtab(dtab))
      Future.value(ParseRsp(Some(result)))

    case Throw(exc) =>
      val e = ParseRsp.Error(Option(exc.getMessage), None)
      val result = ParseRsp.OneofResult.Error(e)
      Future.value(ParseRsp(Some(result)))
  }

  private[this] val WildcardElem =
    ProtoDtab.Dentry.Prefix.Elem(Some(
      ProtoDtab.Dentry.Prefix.Elem.OneofValue.Wildcard(
        ProtoDtab.Dentry.Prefix.Elem.Wildcard()
      )
    ))

  def toProtoPrefix(pfx: Dentry.Prefix): ProtoDtab.Dentry.Prefix =
    ProtoDtab.Dentry.Prefix(pfx.elems.map {
      case Dentry.Prefix.AnyElem => WildcardElem
      case Dentry.Prefix.Label(buf) =>
        ProtoDtab.Dentry.Prefix.Elem(Some(ProtoDtab.Dentry.Prefix.Elem.OneofValue.Label(buf)))
    })

  def fromProtoPrefix(ppfx: ProtoDtab.Dentry.Prefix): Dentry.Prefix =
    Dentry.Prefix(ppfx.elems.map(_prefixElemToProto): _*)

  private[this] val _prefixElemToProto: ProtoDtab.Dentry.Prefix.Elem => Dentry.Prefix.Elem = {
    case WildcardElem => Dentry.Prefix.AnyElem
    case ProtoDtab.Dentry.Prefix.Elem(Some(ProtoDtab.Dentry.Prefix.Elem.OneofValue.Label(buf))) =>
      Dentry.Prefix.Label(buf)
    case elem =>
      throw new IllegalArgumentException(s"Illegal prefix element: $elem")
  }

  def toProtoDtab(dtab: Dtab): ProtoDtab =
    ProtoDtab(dtab.map(toProtoDentry))

  val toProtoDentry: Dentry => ProtoDtab.Dentry = { dentry =>
    val ppfx = toProtoPrefix(dentry.prefix)
    val pdst = toProtoPathNameTree(dentry.dst)
    ProtoDtab.Dentry(Some(ppfx), Some(pdst))
  }

  def fromProtoDtab(pdtab: ProtoDtab): Dtab =
    Dtab(pdtab.dentries.toIndexedSeq.map(fromProtoDentry))

  val fromProtoDentry: ProtoDtab.Dentry => Dentry = {
    case ProtoDtab.Dentry(Some(ppfx), Some(pdst)) =>
      val pfx = fromProtoPrefix(ppfx)
      val dst = fromProtoPathNameTree(pdst)
      Dentry(pfx, dst)
    case dentry =>
      throw new IllegalArgumentException(s"Illegal dentry: $dentry")
  }

  def toProtoPath(path: Path): ProtoPath = ProtoPath(path.elems)
  def fromProtoPath(ppath: ProtoPath): Path = Path(ppath.elems: _*)

  val toProtoPathNameTree: NameTree[Path] => PathNameTree = {
    case NameTree.Neg =>
      PathNameTree(Some(PathNameTree.OneofNode.Nop(PathNameTree.Nop.NEG)))

    case NameTree.Fail =>
      PathNameTree(Some(PathNameTree.OneofNode.Nop(PathNameTree.Nop.FAIL)))

    case NameTree.Empty =>
      PathNameTree(Some(PathNameTree.OneofNode.Nop(PathNameTree.Nop.EMPTY)))

    case NameTree.Leaf(path) =>
      PathNameTree(Some(PathNameTree.OneofNode.Leaf(PathNameTree.Leaf(Some(toProtoPath(path))))))

    case NameTree.Alt(trees@_*) =>
      PathNameTree(Some(PathNameTree.OneofNode.Alt(PathNameTree.Alt(trees.map(toProtoPathNameTree)))))

    case NameTree.Union(trees@_*) =>
      val weighted = trees.map { wt =>
        PathNameTree.Union.Weighted(Some(wt.weight), Some(toProtoPathNameTree(wt.tree)))
      }
      PathNameTree(Some(PathNameTree.OneofNode.Union(PathNameTree.Union(weighted))))
  }

  val fromProtoPathNameTree: PathNameTree => NameTree[Path] = {
    case PathNameTree(Some(PathNameTree.OneofNode.Nop(nop))) =>
      nop match {
        case PathNameTree.Nop.NEG => NameTree.Neg
        case PathNameTree.Nop.FAIL => NameTree.Fail
        case PathNameTree.Nop.EMPTY => NameTree.Empty
      }

    case PathNameTree(Some(PathNameTree.OneofNode.Leaf(PathNameTree.Leaf(Some(path))))) =>
      NameTree.Leaf(fromProtoPath(path))

    case PathNameTree(Some(PathNameTree.OneofNode.Alt(PathNameTree.Alt(ptrees)))) =>
      val trees = ptrees.map(fromProtoPathNameTree)
      NameTree.Alt(trees: _*)

    case PathNameTree(Some(PathNameTree.OneofNode.Union(PathNameTree.Union(ptrees)))) =>
      val trees = ptrees.collect {
        case PathNameTree.Union.Weighted(Some(weight), Some(ptree)) =>
          NameTree.Weighted(weight, fromProtoPathNameTree(ptree))
      }
      NameTree.Union(trees: _*)

    case tree =>
      throw new IllegalArgumentException(s"illegal name tree: $tree")
  }

  def mkDtabReq(ns: String): DtabReq =
    DtabReq(Some(ns))

  def DtabRspError(description: String, code: DtabRsp.Error.Code.Value) = {
    val error = DtabRsp.Error(Some(description), Some(code))
    DtabRsp(Some(DtabRsp.OneofResult.Error(error)))
  }

  val DtabRspNoNamespace =
    DtabRspError("No namespace specified", DtabRsp.Error.Code.BAD_REQUEST)

  val DtabRspNotFound =
    DtabRspError("Namespace not found", DtabRsp.Error.Code.NOT_FOUND)

  val toProtoDtabRsp: VersionedDtab => DtabRsp = { vdtab =>
    val v = ProtoVersionedDtab.Version(Some(vdtab.version))
    val d = toProtoDtab(vdtab.dtab)
    DtabRsp(Some(DtabRsp.OneofResult.Dtab(ProtoVersionedDtab(Some(v), Some(d)))))
  }

  val toProtoDtabRspEv: Try[Option[VersionedDtab]] => VarEventStream.Ev[DtabRsp] = {
    case Return(None) => VarEventStream.Val(DtabRspNotFound)
    case Return(Some(vdtab)) => VarEventStream.Val(toProtoDtabRsp(vdtab))
    case Throw(e) => VarEventStream.End(Return(DtabRspError(e.getMessage, DtabRsp.Error.Code.UNKNOWN)))
  }

  def mkBindReq(ns: String, path: Path, dtab: Dtab): BindReq =
    BindReq(Some(ns), Some(toProtoPath(path)), Some(toProtoDtab(dtab)))

  def BoundTreeRspError(desc: String, code: BoundTreeRsp.Error.Code.Value) = {
    val error = BoundTreeRsp.Error(Some(desc), Some(code))
    BoundTreeRsp(Some(BoundTreeRsp.OneofResult.Error(error)))
  }

  val BoundTreeRspNoNamespace =
    BoundTreeRspError("Namespaces not found", BoundTreeRsp.Error.Code.NOT_FOUND)

  val BoundTreeRspNoName =
    BoundTreeRspError("No name given", BoundTreeRsp.Error.Code.BAD_REQUEST)

  private[this] val BoundTreeNeg = BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.NEG)
  private[this] val BoundTreeFail = BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.FAIL)
  private[this] val BoundTreeEmpty = BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.EMPTY)

  def mkFromProtoBoundNameTree(bindAddr: Path => Var[Addr]): BoundNameTree => NameTree[Name.Bound] = {
    def bindTree(ptree: BoundNameTree): NameTree[Name.Bound] = ptree.node match {
      case Some(BoundTreeNeg) => NameTree.Neg
      case Some(BoundTreeFail) => NameTree.Fail
      case Some(BoundTreeEmpty) => NameTree.Empty

      case Some(BoundNameTree.OneofNode.Leaf(BoundNameTree.Leaf(Some(pid), Some(ppath)))) =>
        val id = fromProtoPath(pid)
        val path = fromProtoPath(ppath)
        NameTree.Leaf(Name.Bound(bindAddr(id), id, path))

      case Some(BoundNameTree.OneofNode.Alt(BoundNameTree.Alt(ptrees))) =>
        val trees = ptrees.map(bindTree)
        NameTree.Alt(trees: _*)

      case Some(BoundNameTree.OneofNode.Union(BoundNameTree.Union(pwtrees))) =>
        val wtrees = pwtrees.collect {
          case BoundNameTree.Union.Weighted(Some(w), Some(t)) =>
            NameTree.Weighted(w, bindTree(t))
        }
        NameTree.Union(wtrees: _*)

      case Some(tree) => throw new IllegalArgumentException(s"Illegal bound tree: $tree")
      case None => throw new IllegalArgumentException("No bound tree")
    }
    bindTree _
  }

  private[this] val toProtoBoundWeightedTree: NameTree.Weighted[Name.Bound] => BoundNameTree.Union.Weighted =
    wt => BoundNameTree.Union.Weighted(Some(wt.weight), Some(toProtoBoundNameTree(wt.tree)))

  val toProtoBoundNameTree: NameTree[Name.Bound] => BoundNameTree = { tree =>
    val ptree = tree match {
      case NameTree.Neg => BoundTreeNeg
      case NameTree.Fail => BoundTreeFail
      case NameTree.Empty => BoundTreeEmpty

      case NameTree.Leaf(name) =>
        name.id match {
          case id: Path =>
            val pid = toProtoPath(id)
            val ppath = toProtoPath(name.path)
            val leaf = BoundNameTree.Leaf(Some(pid), Some(ppath))
            BoundNameTree.OneofNode.Leaf(leaf)

          case _ =>
            BoundNameTree.OneofNode.Nop(BoundNameTree.Nop.NEG)
        }

      case NameTree.Alt(trees@_*) =>
        val ptrees = trees.map(toProtoBoundNameTree)
        BoundNameTree.OneofNode.Alt(BoundNameTree.Alt(ptrees))

      case NameTree.Union(trees@_*) =>
        val ptrees = trees.map(toProtoBoundWeightedTree)
        BoundNameTree.OneofNode.Union(BoundNameTree.Union(ptrees))
    }
    BoundNameTree(Some(ptree))
  }

  val toProtoBoundTreeRsp: NameTree[Name.Bound] => BoundTreeRsp =
    t => BoundTreeRsp(Some(BoundTreeRsp.OneofResult.Tree(toProtoBoundNameTree(t))))

  val toProtoBoundTreeRspEv: Try[NameTree[Name.Bound]] => VarEventStream.Ev[BoundTreeRsp] = {
    case Return(tree) => VarEventStream.Val(toProtoBoundTreeRsp(tree))
    case Throw(e) => VarEventStream.End(Throw(e))
  }

  private[this] val _collectFromEndpoint: PartialFunction[Endpoint, Address] = {
    case Endpoint(Some(_), Some(ipBuf), Some(port), pmeta) =>
      val ipBytes = Buf.ByteArray.Owned.extract(ipBuf)
      val ip = InetAddress.getByAddress(ipBytes)
      val meta = Seq.empty[(String, Any)] ++
        pmeta.flatMap(_.authority).map(Metadata.authority -> _) ++
        pmeta.flatMap(_.nodeName).map(Metadata.nodeName -> _)
      Address.Inet(new InetSocketAddress(ip, port), Addr.Metadata(meta: _*))
  }

  private[this] val _collectToEndpoint: PartialFunction[Address, Endpoint] = {
    case Address.Inet(isa, meta) =>
      val port = isa.getPort
      val pmeta = Endpoint.Meta(
        authority = meta.get(Metadata.authority).map(_.toString),
        nodeName = meta.get(Metadata.nodeName).map(_.toString)
      )
      isa.getAddress match {
        case ip: Inet6Address =>
          Endpoint(
            Some(Endpoint.AddressFamily.INET6),
            Some(Buf.ByteArray.Owned(ip.getAddress)),
            Some(port),
            Some(pmeta)
          )
        case ip =>
          Endpoint(
            Some(Endpoint.AddressFamily.INET4),
            Some(Buf.ByteArray.Owned(ip.getAddress)),
            Some(port),
            Some(pmeta)
          )
      }
  }

  def mkAddrReq(id: Path): AddrReq =
    AddrReq(Some(toProtoPath(id)))

  def AddrError(msg: String): ProtoAddr =
    ProtoAddr(Some(ProtoAddr.OneofResult.Failed(ProtoAddr.Failed(Some(msg)))))

  val AddrErrorNoId = AddrError("No ID provided")

  private[this] val AddrPending = ProtoAddr.OneofResult.Pending(ProtoAddr.Pending())
  private[this] val AddrNeg = ProtoAddr.OneofResult.Neg(ProtoAddr.Neg())
  val toProtoAddrResult: Addr => ProtoAddr.OneofResult = {
    case Addr.Pending => AddrPending
    case Addr.Neg => AddrNeg

    case Addr.Failed(exc) =>
      ProtoAddr.OneofResult.Failed(ProtoAddr.Failed(Option(exc.getMessage)))

    case Addr.Bound(addrs, meta) =>
      val pmeta = ProtoAddr.Bound.Meta(authority = meta.get(Metadata.authority).map(_.toString))
      val bound = ProtoAddr.Bound(addrs.collect(_collectToEndpoint).toSeq, Some(pmeta))
      ProtoAddr.OneofResult.Bound(bound)
  }

  val fromProtoAddr: ProtoAddr => Addr = {
    case ProtoAddr(None) => Addr.Neg
    case ProtoAddr(Some(result)) => result match {
      case ProtoAddr.OneofResult.Pending(_) => Addr.Pending
      case ProtoAddr.OneofResult.Neg(_) => Addr.Neg
      case ProtoAddr.OneofResult.Failed(ProtoAddr.Failed(msg)) =>
        Addr.Failed(msg.getOrElse("No error message provided"))
      case ProtoAddr.OneofResult.Bound(ProtoAddr.Bound(paddrs, pmeta)) =>
        val addrs = paddrs.collect(_collectFromEndpoint)
        val meta = Seq.empty[(String, Any)] ++
          pmeta.flatMap(_.authority).map(Metadata.authority -> _)
        Addr.Bound(addrs.toSet, Addr.Metadata(meta: _*))
    }
  }

  val toProtoAddr: Addr => ProtoAddr =
    a => ProtoAddr(Some(toProtoAddrResult(a)))

  val toProtoAddrEv: Addr => VarEventStream.Ev[ProtoAddr] =
    a => VarEventStream.Val(toProtoAddr(a))

  def mkDelegateTreeReq(ns: String, dtab: Dtab, tree: NameTree[Name.Path]): DelegateTreeReq =
    DelegateTreeReq(Some(ns), Some(toProtoPathNameTree(tree.map(_.path))), Some(toProtoDtab(dtab)))

  def DelegateTreeRspError(description: String, code: DelegateTreeRsp.Error.Code.Value) = {
    val error = DelegateTreeRsp.Error(Some(description), Some(code))
    DelegateTreeRsp(Some(DelegateTreeRsp.OneofResult.Error(error)))
  }

  val DelegateTreeRspNoNamespace =
    DelegateTreeRspError("Namespaces not found", DelegateTreeRsp.Error.Code.NOT_FOUND)

  val DelegateTreeRspNoName =
    DelegateTreeRspError("No name given", DelegateTreeRsp.Error.Code.BAD_REQUEST)

  def mkFromProtoBoundDelegateTree(bindAddr: Path => Var[Addr]): BoundDelegateTree => DelegateTree[Name.Bound] = {
    def bindTree(t: BoundDelegateTree): DelegateTree[Name.Bound] = t match {
      case BoundDelegateTree(Some(dpath0), Some(dentry0), Some(node)) =>
        val dpath = fromProtoPath(dpath0)
        val dentry = fromProtoDentry(dentry0)
        node match {
          case BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.NEG) =>
            DelegateTree.Neg(dpath, dentry)
          case BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.FAIL) =>
            DelegateTree.Fail(dpath, dentry)
          case BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.EMPTY) =>
            DelegateTree.Empty(dpath, dentry)

          case BoundDelegateTree.OneofNode.Leaf(BoundDelegateTree.Leaf(Some(pid), Some(ppath))) =>
            val id = fromProtoPath(pid)
            val path = fromProtoPath(ppath)
            DelegateTree.Leaf(dpath, dentry, Name.Bound(bindAddr(id), id, path))

          case BoundDelegateTree.OneofNode.Alt(BoundDelegateTree.Alt(ptrees)) =>
            val trees = ptrees.map(bindTree)
            DelegateTree.Alt(dpath, dentry, trees: _*)

          case BoundDelegateTree.OneofNode.Union(BoundDelegateTree.Union(ptrees)) =>
            val trees = ptrees.collect {
              case BoundDelegateTree.Union.Weighted(Some(weight), Some(ptree)) =>
                DelegateTree.Weighted(weight, bindTree(ptree))
            }
            DelegateTree.Union(dpath, dentry, trees: _*)

          case BoundDelegateTree.OneofNode.Delegate(ptree) =>
            DelegateTree.Delegate(dpath, dentry, bindTree(ptree))

          case BoundDelegateTree.OneofNode.Exception(msg) =>
            DelegateTree.Exception(dpath, dentry, DelegateException(msg))

          case tree =>
            throw new IllegalArgumentException(s"illegal delegate tree node: $node")
        }

      case tree =>
        throw new IllegalArgumentException(s"illegal delegate tree: $tree")
    }

    bindTree _
  }

  private[this] val toProtoDelegateWeightedTree: DelegateTree.Weighted[Name.Bound] => BoundDelegateTree.Union.Weighted =
    wt => BoundDelegateTree.Union.Weighted(Some(wt.weight), Some(toProtoDelegateTree(wt.tree)))

  private[this] def mkBoundDelegateTree(
    path: Path,
    dentry: Option[Dentry],
    node: BoundDelegateTree.OneofNode
  ): BoundDelegateTree = {
    val p = toProtoPath(path)
    val d = dentry.map(toProtoDentry)
    BoundDelegateTree(Some(p), d, Some(node))
  }

  private[this] def mkBoundDelegateTree(
    path: Path,
    dentry: Dentry,
    node: BoundDelegateTree.OneofNode
  ): BoundDelegateTree = mkBoundDelegateTree(path, Some(dentry), node)

  private[this] def mkBoundDelegateTreeLeaf(name: Name.Bound): Option[BoundDelegateTree.Leaf] =
    name.id match {
      case id: Path =>
        val pid = toProtoPath(id)
        val ppath = toProtoPath(name.path)
        Some(BoundDelegateTree.Leaf(Some(pid), Some(ppath)))
      case _ => None
    }

  val toProtoDelegateTree: DelegateTree[Name.Bound] => BoundDelegateTree = {
    case DelegateTree.Neg(p, d) =>
      mkBoundDelegateTree(p, d, BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.NEG))
    case DelegateTree.Fail(p, d) =>
      mkBoundDelegateTree(p, d, BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.FAIL))
    case DelegateTree.Empty(p, d) =>
      mkBoundDelegateTree(p, d, BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.EMPTY))

    case DelegateTree.Delegate(p, d, t) =>
      mkBoundDelegateTree(p, d, BoundDelegateTree.OneofNode.Delegate(toProtoDelegateTree(t)))
    case DelegateTree.Exception(p, d, e) =>
      mkBoundDelegateTree(p, d, BoundDelegateTree.OneofNode.Exception(e.getMessage))
    case DelegateTree.Transformation(p, desc, n, t) =>
      val leaf = mkBoundDelegateTreeLeaf(n)
      val ptrans = BoundDelegateTree.Transformation(Some(desc), leaf, Some(toProtoDelegateTree(t)))
      mkBoundDelegateTree(p, None, BoundDelegateTree.OneofNode.Transformation(ptrans))

    case DelegateTree.Leaf(p, d, name) =>
      val node = mkBoundDelegateTreeLeaf(name) match {
        case None => BoundDelegateTree.OneofNode.Nop(BoundDelegateTree.Nop.NEG)
        case Some(leaf) => BoundDelegateTree.OneofNode.Leaf(leaf)
      }
      mkBoundDelegateTree(p, d, node)

    case DelegateTree.Alt(p, d, trees@_*) =>
      val ptrees = trees.map(toProtoDelegateTree)
      val node = BoundDelegateTree.OneofNode.Alt(BoundDelegateTree.Alt(ptrees))
      mkBoundDelegateTree(p, d, node)

    case DelegateTree.Union(p, d, trees@_*) =>
      val ptrees = trees.map(toProtoDelegateWeightedTree)
      val node = BoundDelegateTree.OneofNode.Union(BoundDelegateTree.Union(ptrees))
      mkBoundDelegateTree(p, d, node)
  }

  val toProtoDelegateTreeRsp: DelegateTree[Name.Bound] => DelegateTreeRsp =
    t => DelegateTreeRsp(Some(DelegateTreeRsp.OneofResult.Tree(toProtoDelegateTree(t))))

  val toProtoDelegateTreeRspEv: Try[DelegateTree[Name.Bound]] => VarEventStream.Ev[DelegateTreeRsp] = {
    case Return(tree) => VarEventStream.Val(toProtoDelegateTreeRsp(tree))
    case Throw(e) => VarEventStream.End(Throw(e))
  }

  case class DelegateException(msg: String) extends Throwable(msg) with NoStackTrace
}
