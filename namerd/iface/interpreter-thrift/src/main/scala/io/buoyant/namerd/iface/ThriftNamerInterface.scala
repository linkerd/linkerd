package io.buoyant.namerd.iface

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{Addr, Address, Dtab, Name, Namer, NameTree, Path}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namerd.iface.{thriftscala => thrift}
import io.buoyant.namerd.Ns
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

object ThriftNamerInterface {

  val TVoid = thrift.Void()

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
      synchronized {
        if (!current.exists(_.value == v)) {
          val obs = Observation(nextStamp(), v)
          val previous = pending
          current = Some(obs)
          pending = new Promise[Observation]
          previous.setValue(obs)
        }
      }
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

  private case class AddrObserver(
    addr: Var[Addr],
    stamper: Stamper
  ) extends Observer[Option[Addr.Bound]] {
    protected[this] def nextStamp() = stamper()
    protected[this] val updater = addr.changes.respond {
      case bound: Addr.Bound => update(Return(Some(bound)))
      case Addr.Neg => update(Return(None))
      case Addr.Failed(e) => update(Throw(e))
      case Addr.Pending =>
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

  private val DefaultNamer: (Path, Namer) = Path.empty -> Namer.global
}

/**
 * Exposes a polling interface to Namers.
 */
class ThriftNamerInterface(
  interpreters: Ns => NameInterpreter,
  namers: Map[Path, Namer],
  stamper: ThriftNamerInterface.Stamper,
  retryIn: () => Duration
) extends thrift.Namer.FutureIface {
  import ThriftNamerInterface._

  private[this] val log = Logger.get(getClass.getName)

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

  /**
   * Refine a Name (Path) to a NameTree[Path] in a given (Dtab)
   * namespace.
   *
   * Client IDs are currently ignored, but may be used later for
   * debugging, rate limiting, etc.
   */
  def bind(req: thrift.BindReq): Future[thrift.Bound] = {
    val thrift.BindReq(dtabstr, ref@thrift.NameRef(reqStamp, reqName, ns), _) = req
    val dtab = Dtab.read(dtabstr)
    mkPath(reqName) match {
      case Path.empty =>
        Trace.recordBinary("namerd.srv/bind.err", "empty path")
        val failure = thrift.BindFailure("empty path", Int.MaxValue, ref, ns)
        Future.exception(failure)

      case path =>
        Trace.recordBinary("namerd.srv/bind.ns", ns)
        Trace.recordBinary("namerd.srv/bind.path", path.show)

        val bindingObserver = observeBind(ns, dtab, path)
        bindingObserver(reqStamp).transform {
          case Return((TStamp(tstamp), nameTree)) =>
            Trace.recordBinary("namerd.srv/bind.tree", nameTree.show)
            val (root, nodes, _) = mkTree(nameTree)
            Future.value(thrift.Bound(tstamp, thrift.BoundTree(root, nodes), ns))

          case Throw(e) =>
            Trace.recordBinary("namerd.srv/bind.fail", e.toString)
            log.error(e, "binding name %s", path.show)
            val failure = thrift.BindFailure(e.getMessage, retryIn().inSeconds, ref, ns)
            Future.exception(failure)
        }
    }
  }

  private[this] val bindingCacheMu = new {}
  private[this] var bindingCache: Map[(Ns, Dtab, Path), BindingObserver] = Map.empty
  private[this] def observeBind(ns: String, dtab: Dtab, path: Path): BindingObserver =
    bindingCacheMu.synchronized {
      val key = (ns, dtab, path)
      bindingCache.get(key) match {
        case Some(obs) =>
          Trace.recordBinary("namerd.srv/bind.cached", true)
          obs
        case None =>
          Trace.recordBinary("namerd.srv/bind.cached", false)
          val obs = BindingObserver(interpreters(ns).bind(dtab, path), stamper)
          bindingCache += (key -> obs)
          obs
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
    val thrift.AddrReq(ref@thrift.NameRef(reqStamp, reqName, _), _) = req
    mkPath(reqName) match {
      case Path.empty =>
        Trace.recordBinary("namerd.srv/addr.err", "empty path")
        val failure = thrift.AddrFailure("empty path", Int.MaxValue, ref)
        Future.exception(failure)

      case path =>
        Trace.recordBinary("namerd.srv/addr.path", path.show)
        val addrObserver = observeAddr(path)
        addrObserver(reqStamp).transform {
          case Return((newStamp, None)) =>
            Trace.recordBinary("namerd.srv/addr.result", "neg")
            val addr = thrift.Addr(TStamp(newStamp), thrift.AddrVal.Neg(TVoid))
            Future.value(addr)

          case Return((newStamp, Some(bound@Addr.Bound(addrs, meta)))) =>
            Trace.recordBinary("namerd.srv/addr.result", bound.toString)
            val taddrs = addrs.collect {
              case Address.Inet(isa, _) =>
                // TODO translate metadata (weight info, latency compensation, etc)
                val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
                thrift.TransportAddress(ip, isa.getPort)
            }
            val addr = thrift.Addr(TStamp(newStamp), thrift.AddrVal.Bound(thrift.BoundAddr(taddrs)))
            Future.value(addr)

          case Throw(NonFatal(e)) =>
            Trace.recordBinary("namerd.srv/addr.fail", e.toString)
            log.error(e, "resolving addr %s", path.show)
            val failure = thrift.AddrFailure(e.getMessage, Int.MaxValue, ref)
            Future.exception(failure)

          case Throw(e) =>
            Trace.recordBinary("namerd.srv/addr.fail", e.toString)
            log.error(e, "resolving addr %s", path.show)
            Future.exception(e)
        }
    }
  }

  private[this] val addrCacheMu = new {}
  private[this] var addrCache: Map[Path, AddrObserver] = Map.empty
  private[this] def observeAddr(id: Path): AddrObserver =
    addrCacheMu.synchronized {
      addrCache.get(id) match {
        case Some(obs) =>
          Trace.recordBinary("namerd.srv/addr.cached", true)
          obs

        case None =>
          Trace.recordBinary("namerd.srv/addr.cached", false)
          val resolution = bindAddrId(id).run.flatMap {
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
          val obs = AddrObserver(resolution, stamper)
          addrCache += (id -> obs)
          obs
      }
    }

  private[this] def bindAddrId(id: Path): Activity[NameTree[Name.Bound]] = {
    val (pfx, namer) = namers.find { case (p, _) => id.startsWith(p) }.getOrElse(DefaultNamer)
    namer.bind(NameTree.Leaf(id.drop(pfx.size)))
  }
}

