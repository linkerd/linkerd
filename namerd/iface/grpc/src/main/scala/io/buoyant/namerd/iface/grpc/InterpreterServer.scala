package io.buoyant.namerd
package iface.grpc

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.{Addr, Dtab, Namer, NameTree, Path}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{Stream, EventStream}
import io.buoyant.namer.ConfiguredDtabNamer
import io.buoyant.proto.namerd.{Addr => ProtoAddr, VersionedDtab => ProtoVersionedDtab, _}

object InterpreterServer {
  import InterpreterProto._

  def apply(store: DtabStore, namers: Map[Path, Namer], stats: StatsReceiver): Interpreter.Server =
    new Interpreter.Server(Iface(store, namers, stats))

  private[this] val DefaultNamer: (Path, Namer) = Path.empty -> Namer.global

  case class Iface(store: DtabStore, namers: Map[Path, Namer], stats: StatsReceiver)
    extends Interpreter {

    override def parse(req: ParseReq): Future[ParseRsp] =
      Future {
        req.text match {
          case Some(txt) => Dtab.read(txt)
          case None => Dtab.empty
        }
      }.transform(toProtoParseRsp)

    override def getDtab(req: DtabReq): Future[DtabRsp] =
      req.ns match {
        case None => Future.value(DtabRspNoNamespace)
        case Some(ns) => store.observe(ns).toFuture.transform(_transformDtabRsp)
      }

    override def streamDtab(req: DtabReq): Stream[DtabRsp] =
      req.ns match {
        case None => Stream.value(DtabRspNoNamespace)
        case Some(ns) => EventStream(store.observe(ns).values.map(toProtoDtabRspEv))
      }

    override def getBoundTree(req: BindReq): Future[BoundTreeRsp] =
      req.ns match {
        case None => Future.value(BoundTreeRspNoNamespace)
        case Some(ns) =>
          req.name match {
            case None => Future.value(BoundTreeRspNoName)
            case Some(pname) if pname.elems.isEmpty => Future.value(BoundTreeRspNoName)
            case Some(pname) =>
              val dtab = req.dtab match {
                case None => Dtab.empty
                case Some(d) => fromProtoDtab(d)
              }
              val name = fromProtoPath(pname)
              bind(ns, dtab, name).toFuture.map(toProtoBoundTreeRsp)
          }
      }

    override def streamBoundTree(req: BindReq): Stream[BoundTreeRsp] =
      req.ns match {
        case None => Stream.value(BoundTreeRspNoNamespace)
        case Some(ns) =>
          req.name match {
            case None => Stream.value(BoundTreeRspNoName)
            case Some(pname) if pname.elems.isEmpty => Stream.value(BoundTreeRspNoName)
            case Some(pname) =>
              val dtab = req.dtab match {
                case None => Dtab.empty
                case Some(d) => fromProtoDtab(d)
              }
              val name = fromProtoPath(pname)
              val ev = bind(ns, dtab, name).values.map(toProtoBoundTreeRspEv)
              EventStream(ev)
          }
      }

    private[this] def bind(ns: String, localDtab: Dtab, name: Path) = {
      val dtabVar = store.observe(ns).map(_extractDtab)
      val interpreter = ConfiguredDtabNamer(dtabVar, namers.toSeq)
      interpreter.bind(localDtab, name)
    }

    override def getAddr(req: AddrReq): Future[ProtoAddr] = req.id match {
      case None => Future.value(AddrErrorNoId)
      case Some(pid) if pid.elems.isEmpty => Future.value(AddrErrorNoId)
      case Some(pid) => bindAddr(fromProtoPath(pid)).changes.toFuture.map(toProtoAddr)
    }

    override def streamAddr(req: AddrReq): Stream[ProtoAddr] = req.id match {
      case None => Stream.value(AddrErrorNoId)
      case Some(pid) if pid.elems.isEmpty => Stream.value(AddrErrorNoId)
      case Some(pid) => EventStream(bindAddr(fromProtoPath(pid)).map(toProtoAddrEv))
    }

    private[this] def bindAddr(id: Path): Var[Addr] = {
      val (pfx, namer) = namers
        .find { case (pfx, _) => id.startsWith(pfx) }
        .getOrElse(DefaultNamer)

      namer.bind(NameTree.Leaf(id.drop(pfx.size))).run.flatMap {
        case Activity.Pending => Var.value(Addr.Pending)
        case Activity.Failed(e) => Var.value(Addr.Failed(e))
        case Activity.Ok(tree) => tree match {
          case NameTree.Leaf(bound) => bound.addr
          case NameTree.Empty => Var.value(Addr.Bound())
          case NameTree.Fail => Var.value(Addr.Failed("name tree failed"))
          case NameTree.Neg => Var.value(Addr.Neg)
          case NameTree.Alt(_) | NameTree.Union(_) =>
            Var.value(Addr.Failed(s"${id.show} is not a concrete bound id"))
        }
      }
    }
  }

  private[this] val _extractDtab: Option[VersionedDtab] => Dtab = {
    case None => Dtab.empty
    case Some(VersionedDtab(dtab, _)) => dtab
  }

  private[this] val _transformDtabRsp: Try[Option[VersionedDtab]] => Future[DtabRsp] = {
    case Return(None) => Future.value(DtabRspNotFound)
    case Return(Some(vdtab)) => Future.value(toProtoDtabRsp(vdtab))
    case Throw(e) => Future.value(DtabRspError(e.getMessage, DtabRsp.Error.Code.UNKNOWN))
  }

}
