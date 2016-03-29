package io.buoyant.namerd.iface

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{Addr, Address, Dentry, Dtab, Name, NameTree, Path}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.iface.{thriftscala => thrift}
import io.buoyant.namerd.Ns
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

object ThriftNamerInterface {

  val TVoid = thrift.Void()

  type TDtab = Seq[thrift.Dentry]
  object TDtab {
    val empty: TDtab = Seq.empty
    def apply(dtab: Seq[Dentry]): TDtab =
      dtab.map { case Dentry(pfx, dst) => thrift.Dentry(TPath(pfx), dst.show) }
  }

  // Utilities for converting between thrift & finagle Paths
  type TPath = Seq[ByteBuffer]
  object TPath {
    val empty: TPath = Seq.empty

    def apply(elems: String*): TPath =
      elems.map(e => ByteBuffer.wrap(e.getBytes))

    def apply(path: Path): TPath =
      path.elems.map { buf =>
        val Buf.ByteBuffer.Owned(t) = Buf.ByteBuffer.coerce(buf)
        t
      }
  }

  def mkPath(tpath: TPath): Path =
    Path(tpath.map(Buf.ByteBuffer.Owned(_)): _*)

  def mkDtab(tdentries: Seq[thrift.Dentry]): Dtab = {
    val dentries = tdentries.toIndexedSeq.map { tdentry =>
      Dentry(mkPath(tdentry.prefix), NameTree.read(tdentry.dst))
    }
    Dtab(dentries)
  }

  // Utilities for converting between thrift & finagle Stamps
  type Stamp = Buf
  type TStamp = ByteBuffer

  object TStamp {
    val empty = ByteBuffer.wrap(Array.empty)

    def apply(s: Stamp): TStamp = {
      val Buf.ByteBuffer.Owned(t) = Buf.ByteBuffer.coerce(s)
      t
    }

    def unapply(s: Stamp): Option[TStamp] = Some(apply(s))

    def mk(v: Long): TStamp = {
      val bbuf = ByteBuffer.allocate(8)
      bbuf.putLong(v)
      bbuf.flip()
      bbuf
    }
  }

  object Stamp {
    val empty: Stamp = Buf.Empty
    def apply(t: TStamp): Stamp = Buf.ByteBuffer.Owned(t)

    def mk(v: Long): Stamp = Stamp(TStamp.mk(v))
  }

  type Stamper = () => Stamp

  /**
   * A utility for generating stamps unique to an instance.
   */
  private[namerd] class LocalStamper extends Stamper {
    private[this] val counter = new AtomicLong(Long.MinValue)

    def apply(): Stamp = Stamp.mk(counter.getAndIncrement())
  }

  /**
   * A utility that supports watching of a Var via a long-polling API.
   */
  private[namerd] trait Observer[T] extends Closable {
    private[this] case class Observation(stamp: Stamp, value: Try[T]) {
      lazy val future = Future.const(value.map(stamp -> _))
    }

    private[this] var current: Option[Observation] = None
    private[this] var pending = new Promise[Observation]

    protected[this] def nextStamp(): Stamp

    protected[this] final def update(v: Try[T]): Unit = {
      val obs = Observation(nextStamp(), v)
      val promise = synchronized {
        val previous = pending
        current = Some(obs)
        pending = new Promise[Observation]
        previous
      }
      promise.setValue(obs)
    }

    /** Responsible for calling update() */
    protected[this] def updater: Closable

    final def apply(): Future[(Stamp, T)] =
      synchronized((current, pending)) match {
        case (Some(current), _) => current.future
        case (None, pending) => pending.flatMap(_.future)
      }

    final def apply(stamp: Stamp): Future[(Stamp, T)] =
      synchronized((current, pending)) match {
        case (Some(current), _) if current.stamp != stamp => current.future
        case (_, pending) => pending.flatMap(_.future)
      }

    final def apply(tstamp: TStamp): Future[(Stamp, T)] =
      apply(Stamp(tstamp))

    final def close(t: Time) = updater.close(t).ensure {
      synchronized {
        current = None
      }
    }
  }

  private case class BindingObserver(
    trees: Activity[NameTree[Name.Bound]],
    stamper: Stamper
  ) extends Observer[NameTree[Name.Bound]] {
    protected[this] def nextStamp() = stamper()
    protected[this] val updater = trees.values.respond(update)
  }

  private case class AddrObserver(
    addr: Var[Addr],
    stamper: Stamper
  ) extends Observer[Option[Addr.Bound]] {
    protected[this] def nextStamp() = stamper()
    protected[this] val updater = addr.changes.respond {
      case bound: Addr.Bound => update(Return(Some(bound)))
      case Addr.Failed(e) => update(Throw(e))
      case Addr.Neg => update(Return(None))
      case Addr.Pending =>
    }
  }

}

/**
 * Exposes a polling interface to Namers.
 */
class ThriftNamerInterface(
  val namers: Ns => NameInterpreter,
  stamper: ThriftNamerInterface.Stamper,
  retryIn: () => Duration
) extends thrift.Namer.FutureIface {
  import ThriftNamerInterface._

  /*
   * We keep a cache of observations.  Each observer keeps track of
   * the most recently observed state, and provides a Future that will
   * be satisfied when the next update occurs.
   *
   * XXX we need a mechanism to evict observers that haven't been
   * observed in $period or at least limit the size of these caches.
   *
   * XXX I expect this could be more efficient, even lockless.
   */

  private[this] val bindingCacheMu = new {}
  private[this] var bindingCache: Map[(String, Dtab, Path), Activity[NameTree[Name.Bound]]] = Map.empty
  private[this] def getBind(ns: String, dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] =
    bindingCacheMu.synchronized {
      val key = (ns, dtab, path)
      bindingCache.get(key) match {
        case Some(act) => act
        case None =>
          val act = namers(ns).bind(dtab, path)
          bindingCache += (key -> act)
          act
      }
    }

  private[this] val bindingObserverCacheMu = new {}
  private[this] var bindingObserverCache: Map[(Ns, Dtab, Path), BindingObserver] = Map.empty
  private[this] def observe(ns: String, dtab: Dtab, path: Path): BindingObserver =
    bindingObserverCacheMu.synchronized {
      val key = (ns, dtab, path)
      bindingObserverCache.get(key) match {
        case Some(obs) =>
          Trace.recordBinary("namerd.srv/bind/cached", true)
          obs
        case None =>
          Trace.recordBinary("namerd.srv/bind/cached", false)
          val obs = BindingObserver(getBind(ns, dtab, path), stamper)
          bindingObserverCache += (key -> obs)
          obs
      }
    }

  private[this] val addrObserverCacheMu = new {}
  private[this] var addrObserverCache: Map[Path, AddrObserver] = Map.empty
  private[this] def cacheBoundAddrs(nt: NameTree[Name.Bound]): Unit = nt match {
    case NameTree.Neg =>
    case NameTree.Empty =>
    case NameTree.Fail =>
    case NameTree.Alt(trees@_*) =>
      trees.foreach(cacheBoundAddrs)
    case NameTree.Union(trees@_*) =>
      trees.foreach(w => cacheBoundAddrs(w.tree))
    case NameTree.Leaf(bound) =>
      bound.id match {
        case id: Path =>
          addrObserverCacheMu.synchronized {
            if (!addrObserverCache.contains(id)) {
              addrObserverCache += (id -> AddrObserver(bound.addr, stamper))
            }
          }
      }
  }

  private[this] def getAddrObserver(path: Path): Option[AddrObserver] =
    addrObserverCacheMu.synchronized(addrObserverCache).get(path)

  /**
   * Refine a Name (Path) to a NameTree[Path] in a given (Dtab)
   * namespace.
   *
   * Client IDs are currently ignored, but may be used later for
   * debugging, rate limiting, etc.
   */
  def bind(req: thrift.BindReq): Future[thrift.Bound] = {
    val thrift.BindReq(tdentries, ref@thrift.NameRef(reqStamp, reqName, ns), _) = req
    val dtab = mkDtab(tdentries)
    val path = mkPath(reqName)
    Trace.recordBinary("namerd/srv/bind/ns", ns)
    Trace.recordBinary("namerd/srv/bind/path", path.show)

    val bindingObserver = observe(ns, dtab, path)
    bindingObserver(reqStamp).transform {
      case Throw(e) =>
        Trace.recordBinary("namerd/srv/bind/fail", e.toString)
        val failure = thrift.BindFailure(e.getMessage, retryIn().inSeconds, ref, ns)
        Future.exception(failure)

      case Return((TStamp(tstamp), nameTree)) =>
        Trace.recordBinary("namerd/srv/bind/tree", nameTree.show)
        cacheBoundAddrs(nameTree)
        val (root, nodes, _) = mkTree(nameTree)
        Future.value(thrift.Bound(tstamp, thrift.BoundTree(root, nodes), ns))
    }
  }

  private[this] def mkTree(
    nt: NameTree[Name.Bound],
    nextId: Int = 0
  ): (thrift.BoundNode, Map[Int, thrift.BoundNode], Int) =
    nt match {
      case NameTree.Neg => (thrift.BoundNode.Neg(TVoid), Map.empty, nextId)
      case NameTree.Empty => (thrift.BoundNode.Empty(TVoid), Map.empty, nextId)
      case NameTree.Fail => (thrift.BoundNode.Fail(TVoid), Map.empty, nextId)

      case NameTree.Alt(trees@_*) =>
        case class Agg(
          nextId: Int,
          trees: Seq[Int] = Nil,
          nodes: Map[Int, thrift.BoundNode] = Map.empty
        ) {
          def +(tree: NameTree[Name.Bound]): Agg = {
            val id = nextId
            val (node, childNodes, nextNextId) = mkTree(tree, id + 1)
            copy(
              nextId = nextNextId,
              trees = trees :+ id,
              nodes = nodes ++ childNodes + (id -> node)
            )
          }
        }
        val agg = trees.foldLeft(Agg(nextId))(_ + _)
        (thrift.BoundNode.Alt(agg.trees), agg.nodes, agg.nextId)

      case NameTree.Union(trees@_*) =>
        case class Agg(
          nextId: Int,
          trees: Seq[thrift.WeightedNodeId] = Nil,
          nodes: Map[Int, thrift.BoundNode] = Map.empty
        ) {
          def +(wt: NameTree.Weighted[Name.Bound]): Agg = {
            val id = nextId
            val (node, childNodes, nextNextId) = mkTree(wt.tree, id + 1)
            copy(
              nextId = nextNextId,
              trees = trees :+ thrift.WeightedNodeId(wt.weight, id),
              nodes = nodes ++ childNodes + (id -> node)
            )
          }
        }
        val agg = trees.foldLeft(Agg(nextId))(_ + _)
        (thrift.BoundNode.Weighted(agg.trees), agg.nodes, agg.nextId)

      case NameTree.Leaf(bound) =>
        val node = bound.id match {
          case id: Path =>
            thrift.BoundNode.Leaf(thrift.BoundName(TPath(id), TPath(bound.path)))
          case _ => thrift.BoundNode.Neg(TVoid)
        }
        (node, Map.empty, nextId)
    }

  /**
   * Observe a bound address pool.
   *
   * Addresses are done by bound ID (Path), which must have been
   * resolved via `bind` previously.
   *
   * Client IDs are currently ignored, but may be used later for
   * debugging, rate limiting, etc.
   */
  def addr(req: thrift.AddrReq): Future[thrift.Addr] = {
    val thrift.AddrReq(ref@thrift.NameRef(reqStamp, reqName, ns), _) = req
    val path = mkPath(reqName)
    Trace.recordBinary("namerd/srv/addr/path", path.show)
    getAddrObserver(path) match {
      case Some(observer) =>
        // the bound addr has already been cached.
        respondAddr(observer, reqStamp)

      case None =>
        // if it hasn't already been cached, try to bind the addr's id:
        getBind(ns, Dtab.empty, path).values.toFuture().flatMap(Future.const(_)).transform {
          case Return(tree) =>
            cacheBoundAddrs(tree)
            getAddrObserver(path) match {
              case Some(observer) =>
                respondAddr(observer, reqStamp)

              case None =>
                val e = thrift.AddrFailure(s"no bound address for ${path.show}", -1, ref)
                Future.exception(e)
            }

          case Throw(e) =>
            val msg = s"error binding ${path.show}: ${e.getMessage}"
            Future.exception(thrift.AddrFailure(msg, retryIn().inSeconds, ref))
        }
    }
  }

  private[this] def respondAddr(observer: AddrObserver, reqStamp: TStamp): Future[thrift.Addr] = {
    observer(reqStamp).map {
      case (newStamp, None) =>
        thrift.Addr(TStamp(newStamp), thrift.AddrVal.Neg(TVoid))

      case (newStamp, Some(Addr.Bound(addrs, meta))) =>
        val taddrs = addrs.collect {
          case Address.Inet(isa, _) =>
            // TODO translate metadata (weight info, latency compensation, etc)
            val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
            thrift.TransportAddress(ip, isa.getPort)
        }
        thrift.Addr(TStamp(newStamp), thrift.AddrVal.Bound(thrift.BoundAddr(taddrs)))
    }

  }
}

