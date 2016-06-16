package io.buoyant.namerd.iface

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import com.twitter.util.TimeConversions._
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import java.net.{InetAddress, InetSocketAddress}

class ThriftNamerClient(
  client: thrift.Namer.FutureIface,
  namespace: String,
  clientId: Path = Path.empty,
  _timer: Timer = DefaultTimer.twitter
) extends NameInterpreter with Delegator {
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

  private[this] def mkDelegateTree(dt: thrift.DelegateTree): DelegateTree[Name.Bound] = {
    def mk(node: thrift.DelegateNode): DelegateTree[Name.Bound] = {
      node.contents match {
        case thrift.DelegateContents.Excpetion(thrown) =>
          DelegateTree
            .Exception(
              mkPath(node.path),
              Dentry.read(node.dentry),
              new Exception(thrown)
            )
        case thrift.DelegateContents.Empty(_) =>
          DelegateTree.Empty(mkPath(node.path), Dentry.read(node.dentry))
        case thrift.DelegateContents.Fail(_) =>
          DelegateTree.Fail(mkPath(node.path), Dentry.read(node.dentry))
        case thrift.DelegateContents.Neg(_) =>
          DelegateTree.Neg(mkPath(node.path), Dentry.read(node.dentry))
        case thrift.DelegateContents.Delegate(child) =>
          DelegateTree.Delegate(mkPath(node.path), Dentry.read(node.dentry), mk(dt.nodes(child)))
        case thrift.DelegateContents.BoundLeaf(thrift.BoundName(tid, tresidual)) =>
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
          val bound = Name.Bound(addr, id, residual)
          DelegateTree.Leaf(mkPath(node.path), Dentry.read(node.dentry), bound)
        case thrift.DelegateContents.Alt(children) =>
          val alts = children.map(dt.nodes).map(mk)
          DelegateTree.Alt(mkPath(node.path), Dentry.read(dt.root.dentry), alts: _*)
        case thrift.DelegateContents.Weighted(children) =>
          val weights = children.map { child =>
            DelegateTree.Weighted(child.weight, mk(dt.nodes(child.id)))
          }
          DelegateTree.Union(mkPath(node.path), Dentry.read(dt.root.dentry), weights: _*)
        case thrift.DelegateContents.PathLeaf(leaf) =>
          throw new IllegalArgumentException("delegation cannot accept path names")
      }
    }
    mk(dt.root)
  }

  override def delegate(
    dtab: Dtab,
    tree: DelegateTree[Name.Path]
  ): Activity[DelegateTree[Bound]] = {
    val tdtab = dtab.show
    val (root, nodes, _) = ThriftNamerInterface.mkDelegateTree(tree)
    val ttree = thrift.DelegateTree(root, nodes)

    val states = Var.async[Activity.State[DelegateTree[Name.Bound]]](Activity.Pending) { states =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp): Unit = if (!stopped) {

        val req = thrift.DelegateReq(tdtab, thrift.Delegation(stamp0, ttree, namespace), tclientId)
        pending = Trace.letClear(client.delegate(req)).respond {
          case Return(thrift.Delegation(stamp1, ttree, _)) =>
            states() = Try(mkDelegateTree(ttree)) match {
              case Return(tree) =>
                Activity.Ok(tree)
              case Throw(e) =>
                Activity.Failed(e)
            }
            loop(stamp1)

          case Throw(e@thrift.DelegationFailure(reason)) =>
            log.error(s"delegation failed: $reason")
            states() = Activity.Failed(e)

          case Throw(e) =>
            log.error(e, "delegation failed")
            states() = Activity.Failed(e)
        }
      }

      loop(TStamp.empty)
      Closable.make { deadline =>
        stopped = true
        Future.Unit
      }
    }

    Activity(states)
  }

  override def dtab: Activity[Dtab] = {

    val states = Var.async[Activity.State[Dtab]](Activity.Pending) { states =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp): Unit = if (!stopped) {

        val req = thrift.DtabReq(stamp0, namespace, tclientId)
        pending = Trace.letClear(client.dtab(req)).respond {
          case Return(thrift.DtabRef(stamp1, dtab)) =>
            states() = Try(Dtab.read(dtab)) match {
              case Return(dtab) =>
                Activity.Ok(dtab)
              case Throw(e) =>
                Activity.Failed(e)
            }
            loop(stamp1)

          case Throw(e@thrift.DtabFailure(reason)) =>
            log.error(s"dtab $namespace lookup failed: $reason")
            states() = Activity.Failed(e)

          case Throw(e) =>
            log.error(e, s"dtab $namespace lookup failed")
            states() = Activity.Failed(e)
        }
      }

      loop(TStamp.empty)
      Closable.make { deadline =>
        log.debug(s"dtab $namespace released")
        stopped = true
        Future.Unit
      }
    }

    Activity(states)
  }
}
