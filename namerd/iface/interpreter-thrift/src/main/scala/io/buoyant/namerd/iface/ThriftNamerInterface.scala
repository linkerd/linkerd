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

    final def close(t: Time): Future[Unit] =
      synchronized {
        current = None
        updater.close(t)
      }

  }

  private case class BindingObserver(
    trees: Activity[NameTree[Name.Bound]],
    stamper: Stamper
  ) extends Observer[NameTree[Name.Bound]] {
    protected[this] def nextStamp() = stamper()
    protected[this] val updater = trees.values.respond(update)
  }

  sealed trait Resolution
  object Resolution {
    case class Resolved(addr: Addr) extends Resolution
    object Released extends Resolution
  }

  private case class AddrObserver(
    addr: Var[Resolution],
    stamper: Stamper,
    release: () => Unit
  ) extends Observer[Option[Addr.Bound]] {
    protected[this] def nextStamp() = stamper()
    protected[this] val updater = addr.changes.respond {
      case Resolution.Released => release()
      case Resolution.Resolved(addr) => addr match {
        case bound: Addr.Bound => update(Return(Some(bound)))
        case Addr.Neg => update(Return(None))
        case Addr.Failed(e) => update(Throw(e))
        case Addr.Pending =>
      }
    }
  }

  private[this] case class AltAgg(
    nextId: Int,
    trees: Seq[Int] = Nil,
    nodes: Map[Int, thrift.BoundNode] = Map.empty
  ) {
    def +(tree: NameTree[Name.Bound]): AltAgg = {
      val id = nextId
      val (node, childNodes, nextNextId) = mkTree(tree, id + 1)
      copy(
        nextId = nextNextId,
        trees = trees :+ id,
        nodes = nodes ++ childNodes + (id -> node)
      )
    }
  }

  private[this] case class UnionAgg(
    nextId: Int,
    trees: Seq[thrift.WeightedNodeId] = Nil,
    nodes: Map[Int, thrift.BoundNode] = Map.empty
  ) {
    def +(wt: NameTree.Weighted[Name.Bound]): UnionAgg = {
      val id = nextId
      val (node, childNodes, nextNextId) = mkTree(wt.tree, id + 1)
      copy(
        nextId = nextNextId,
        trees = trees :+ thrift.WeightedNodeId(wt.weight, id),
        nodes = nodes ++ childNodes + (id -> node)
      )
    }
  }

  private def mkTree(
    nt: NameTree[Name.Bound],
    nextId: Int = 0
  ): (thrift.BoundNode, Map[Int, thrift.BoundNode], Int) =
    nt match {
      case NameTree.Neg => (thrift.BoundNode.Neg(TVoid), Map.empty, nextId)
      case NameTree.Empty => (thrift.BoundNode.Empty(TVoid), Map.empty, nextId)
      case NameTree.Fail => (thrift.BoundNode.Fail(TVoid), Map.empty, nextId)

      case NameTree.Alt(trees@_*) =>
        val agg = trees.foldLeft(AltAgg(nextId))(_ + _)
        (thrift.BoundNode.Alt(agg.trees), agg.nodes, agg.nextId)

      case NameTree.Union(trees@_*) =>
        val agg = trees.foldLeft(UnionAgg(nextId))(_ + _)
        (thrift.BoundNode.Weighted(agg.trees), agg.nodes, agg.nextId)

      case NameTree.Leaf(bound) =>
        val node = bound.id match {
          case id: Path => thrift.BoundNode.Leaf(thrift.BoundName(TPath(id), TPath(bound.path)))
          case _ => thrift.BoundNode.Neg(TVoid)
        }
        (node, Map.empty, nextId)
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
          Trace.recordBinary("namerd.srv/bind.cached", true)
          obs
        case None =>
          Trace.recordBinary("namerd.srv/bind.cached", false)
          val obs = BindingObserver(getBind(ns, dtab, path), stamper)
          bindingObserverCache += (key -> obs)
          obs
      }
    }

  private[this] val addrObserverCacheMu = new {}
  private[this] var addrObserverCache: Map[Path, AddrObserver] = Map.empty
  private[this] def observeAddr(ns: String, id: Path): AddrObserver =
    addrObserverCacheMu.synchronized {
      addrObserverCache.get(id) match {
        case Some(obs) =>
          Trace.recordBinary("namerd.srv/addr.cached", true)
          obs
        case None =>
          Trace.recordBinary("namerd.srv/addr.cached", false)
          val addr = getBind(ns, Dtab.empty, id).run.flatMap {
            case Activity.Failed(e) => Var.value(Resolution.Resolved(Addr.Failed(e)))
            case Activity.Pending => Var.value(Resolution.Resolved(Addr.Pending))
            case Activity.Ok(NameTree.Leaf(bound)) if bound.id == id =>
              bound.addr.map(Resolution.Resolved(_))
            case _ => Var.value(Resolution.Released)
          }
          val obs = AddrObserver(addr, stamper, () => releaseAddr(id))
          addrObserverCache += (id -> obs)
          obs
      }
    }

  private[this] def releaseAddr(id: Path): Unit =
    addrObserverCacheMu.synchronized {
      addrObserverCache.get(id).foreach(_.close())
      addrObserverCache -= id
    }

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
    Trace.recordBinary("namerd.srv/bind.ns", ns)
    Trace.recordBinary("namerd.srv/bind.path", path.show)

    val bindingObserver = observe(ns, dtab, path)
    bindingObserver(reqStamp).transform {
      case Throw(e) =>
        Trace.recordBinary("namerd.srv/bind.fail", e.toString)
        val failure = thrift.BindFailure(e.getMessage, retryIn().inSeconds, ref, ns)
        Future.exception(failure)

      case Return((TStamp(tstamp), nameTree)) =>
        Trace.recordBinary("namerd.srv/bind.tree", nameTree.show)
        val (root, nodes, _) = mkTree(nameTree)
        Future.value(thrift.Bound(tstamp, thrift.BoundTree(root, nodes), ns))
    }
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
    Trace.recordBinary("namerd.srv/addr.path", path.show)

    val addrObserver = observeAddr(ns, path)
    addrObserver(reqStamp).map {
      case (newStamp, None) =>
        Trace.recordBinary("namerd.srv/addr.result", "neg")
        thrift.Addr(TStamp(newStamp), thrift.AddrVal.Neg(TVoid))

      case (newStamp, Some(bound@Addr.Bound(addrs, meta))) =>
        Trace.recordBinary("namerd.srv/addr.result", bound.toString)
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

