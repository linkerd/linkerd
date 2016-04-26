package io.buoyant.namerd.iface

import com.twitter.finagle.{Addr, Address, Dtab, Name, NameTree, Path}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import com.twitter.util.TimeConversions._
import io.buoyant.namerd.iface.{thriftscala => thrift}
import java.net.{InetAddress, InetSocketAddress}

class ThriftNamerClient(
  client: thrift.Namer.FutureIface,
  namespace: String,
  clientId: Path = Path.empty,
  _timer: Timer = DefaultTimer.twitter
) extends NameInterpreter {
  import ThriftNamerInterface._

  private[this] implicit val log = Logger.get(getClass.getName)
  private[this] implicit val timer = _timer
  private[this] val tclientId = TPath(clientId)

  /*
   * XXX needs proper eviction, etc
   */
  private[this] val bindCacheMu = new {}
  private[this] var bindCache = Map.empty[(Dtab, Path), Activity[NameTree[Name.Bound]]]

  private[this] val addrCacheMu = new {}
  private[this] var addrCache = Map.empty[Path, Var[Addr]]

  def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
    Trace.recordBinary("namerd.client/bind.dtab", dtab.show)
    Trace.recordBinary("namerd.client/bind.path", path.show)
    val key = (dtab, path)
    bindCacheMu.synchronized {
      bindCache.get(key) match {
        case Some(act) =>
          Trace.recordBinary("namerd.client/bind.cached", true)
          act

        case None =>
          Trace.recordBinary("namerd.client/bind.cached", false)
          val act = watchName(dtab, path)
          bindCache += (key -> act)
          act
      }
    }
  }

  private[this] def watchName(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
    val tdtab = dtab.show
    val tpath = TPath(path)

    val states = Var.async[Activity.State[NameTree[Name.Bound]]](Activity.Pending) { states =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp): Unit = if (!stopped) {
        Trace.recordBinary("namerd.client/bind.ns", namespace)
        Trace.recordBinary("namerd.client/bind.path", path.show)

        val req = thrift.BindReq(tdtab, thrift.NameRef(stamp0, tpath, namespace), tclientId)
        pending = Trace.letClear(client.bind(req)).respond {
          case Return(thrift.Bound(stamp1, ttree, _)) =>
            states() = Try(mkTree(ttree)) match {
              case Return(tree) =>
                Trace.recordBinary("namerd.client/bind.tree", tree.show)
                Activity.Ok(tree)
              case Throw(e) =>
                Trace.recordBinary("namerd.client/bind.err", e.toString)
                Activity.Failed(e)
            }
            loop(stamp1)

          case Throw(e@thrift.BindFailure(reason, retry, _, _)) =>
            Trace.recordBinary("namerd.client/bind.fail", reason)
            states() = Activity.Failed(e)
            if (!stopped) {
              pending = Future.sleep(retry.seconds).onSuccess(_ => loop(stamp0))
            }

          // XXX we have to handle other errors, right?
          case Throw(e) =>
            log.error(e, s"""bind ${path.show}""")
            Trace.recordBinary("namerd.client/bind.exc", e.toString)
            states() = Activity.Failed(e)
        }
      }

      loop(TStamp.empty)
      Closable.make { deadline =>
        log.debug(s"""bind released ${path.show}""")
        stopped = true
        Future.Unit
      }
    }

    Activity(states)
  }

  private[this] def mkTree(ttree: thrift.BoundTree): NameTree[Name.Bound] = {
    def mk(node: thrift.BoundNode): NameTree[Name.Bound] = node match {
      case thrift.BoundNode.Neg(_) => NameTree.Neg
      case thrift.BoundNode.Empty(_) => NameTree.Empty
      case thrift.BoundNode.Fail(_) => NameTree.Fail

      case thrift.BoundNode.Leaf(thrift.BoundName(tid, tresidual)) =>
        val residual = mkPath(tresidual)
        val id = mkPath(tid)
        val addr = addrCacheMu.synchronized {
          addrCache.get(id) match {
            case Some(addr) => addr
            case None =>
              val addr = watchAddr(tid)
              addrCache += (id -> addr)
              addr
          }
        }
        NameTree.Leaf(Name.Bound(addr, id, residual))

      case thrift.BoundNode.Alt(ids) =>
        val trees = ids.map { id =>
          ttree.nodes.get(id) match {
            case None => throw new IllegalArgumentException(s"unknown node id: $id")
            case Some(node) => mk(node)
          }
        }
        NameTree.Alt(trees: _*)

      case thrift.BoundNode.Weighted(weightedIds) =>
        val weighted = weightedIds.map {
          case thrift.WeightedNodeId(weight, id) =>
            ttree.nodes.get(id) match {
              case None => throw new IllegalArgumentException(s"unknown node id: $id")
              case Some(node) => NameTree.Weighted(weight, mk(node))
            }
        }
        NameTree.Union(weighted: _*)
    }

    mk(ttree.root)
  }

  private[this] def watchAddr(id: TPath): Var[Addr] = {
    val idPath = mkPath(id).show

    Var.async[Addr](Addr.Pending) { addr =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp): Unit = if (!stopped) {
        Trace.recordBinary("namerd.client/addr.path", idPath)
        val req = thrift.AddrReq(thrift.NameRef(stamp0, id, namespace), tclientId)
        pending = Trace.letClear(client.addr(req)).respond {
          case Return(thrift.Addr(stamp1, thrift.AddrVal.Neg(_))) =>
            addr() = Addr.Neg
            Trace.record("namerd.client/addr.neg")
            loop(stamp1)

          case Return(thrift.Addr(stamp1, thrift.AddrVal.Bound(thrift.BoundAddr(taddrs, _)))) =>
            val addrs = taddrs.map { taddr =>
              val thrift.TransportAddress(ipbb, port, _) = taddr
              val ipBytes = Buf.ByteArray.Owned.extract(Buf.ByteBuffer.Owned(ipbb))
              val ip = InetAddress.getByAddress(ipBytes)
              // TODO convert metadata
              Address(new InetSocketAddress(ip, port))
            }
            // TODO convert metadata
            Trace.recordBinary("namerd.client/addr.bound", addrs)
            addr() = Addr.Bound(addrs.toSet)
            loop(stamp1)

          case Throw(e@thrift.AddrFailure(msg, retry, _)) =>
            Trace.recordBinary("namerd.client/addr.fail", msg)
            addr() = Addr.Failed(e)
            if (!stopped) {
              pending = Future.sleep(retry.seconds).onSuccess(_ => loop(stamp0))
            }

          case Throw(e) =>
            log.error(e, s"addr on $idPath")
            Trace.recordBinary("namerd.client/addr.exc", e.getMessage)
            addr() = Addr.Failed(e)
        }
      }

      loop(TStamp.empty)
      Closable.make { deadline =>
        log.debug(s"addr released $idPath")
        stopped = true
        Future.Unit
      }
    }
  }

}
