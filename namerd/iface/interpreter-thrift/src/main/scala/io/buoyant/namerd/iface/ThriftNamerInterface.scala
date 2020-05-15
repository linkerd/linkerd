package io.buoyant.namerd.iface

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.mux.ClientDiscardedRequestException
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.namer.{DelegateTree, Delegator, Metadata, Paths}
import io.buoyant.namerd.Ns
import io.buoyant.namerd.iface.ThriftNamerInterface.Capacity
import io.buoyant.namerd.iface.thriftscala.{Delegation, DtabRef, DtabReq}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

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

    // an arbitrary non-empty stamp
    def one = mk(1L)

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
    private[this] val counter = {
      // Start with a random long so stamps between multiple instances are unlikely to collide
      val rand = new Random()
      new AtomicLong(rand.nextLong())
    }

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
        case (None, pending) =>
          val p = Promise.attached(pending)
          p.setInterruptHandler {
            case e: Throwable =>
              p.detach()
              p.setException(e)
          }
          p.flatMap(_.future)
      }

    final def apply(stamp: Stamp): Future[(Stamp, T)] =
      synchronized((current, pending)) match {
        case (Some(current), _) if current.stamp != stamp => current.future
        case (_, pending) =>
          val p = Promise.attached(pending)
          p.setInterruptHandler {
            case e: Throwable =>
              p.detach()
              p.setException(e)
          }
          p.flatMap(_.future)
      }

    final def apply(tstamp: TStamp): Future[(Stamp, T)] =
      apply(Stamp(tstamp))

    final def nextValue: Future[(Stamp, T)] = {
      val p = Promise.attached(pending)
      p.setInterruptHandler {
        case e: Throwable =>
          p.detach()
          p.setException(e)
      }
      p.flatMap(_.future)
    }

    final def close(t: Time): Future[Unit] =
      synchronized {
        current = None
        updater.close(t)
      }

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

  private def mkObserver[T](act: Activity[T], stamper: Stamper) = new Observer[T] {
    protected[this] def nextStamp() = stamper()
    protected[this] val updater = act.values.respond(update)
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

  private[this] case class DelegateAltAgg(
    nextId: Int,
    trees: Seq[Int] = Nil,
    nodes: Map[Int, thrift.DelegateNode] = Map.empty
  ) {
    def +(tree: DelegateTree[Name]): DelegateAltAgg = {
      val id = nextId
      val (node, childNodes, nextNextId) = mkDelegateTree(tree, id + 1)
      copy(
        nextId = nextNextId,
        trees = trees :+ id,
        nodes = nodes ++ childNodes + (id -> node)
      )
    }
  }

  private[this] case class DelegateUnionAgg(
    nextId: Int,
    trees: Seq[thrift.WeightedNodeId] = Nil,
    nodes: Map[Int, thrift.DelegateNode] = Map.empty
  ) {
    def +(wt: DelegateTree.Weighted[Name]): DelegateUnionAgg = {
      val id = nextId
      val (node, childNodes, nextNextId) = mkDelegateTree(wt.tree, id + 1)
      copy(
        nextId = nextNextId,
        trees = trees :+ thrift.WeightedNodeId(wt.weight, id),
        nodes = nodes ++ childNodes + (id -> node)
      )
    }
  }

  def mkDelegateTree(
    dt: DelegateTree[Name],
    nextId: Int = 0
  ): (thrift.DelegateNode, Map[Int, thrift.DelegateNode], Int) =
    dt match {
      case DelegateTree.Exception(path, dentry, thrown) =>
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Error(thrown.getMessage)), Map.empty, nextId)
      case DelegateTree.Empty(path, dentry) =>
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Empty(TVoid)), Map.empty, nextId)
      case DelegateTree.Fail(path, dentry) =>
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Fail(TVoid)), Map.empty, nextId)
      case DelegateTree.Neg(path, dentry) =>
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Neg(TVoid)), Map.empty, nextId)
      case DelegateTree.Delegate(path, dentry, tree) =>
        val (node, childNodes, nextNextId) = mkDelegateTree(tree, nextId)
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Delegate(nextNextId)), childNodes + (nextNextId -> node), nextNextId + 1)
      case DelegateTree.Leaf(path, dentry, value) =>
        val contents = value match {
          case bound: Name.Bound =>
            bound.id match {
              case id: Path =>
                thrift.DelegateContents.BoundLeaf(thrift.BoundName(TPath(id), TPath(bound.path)))
              case _ =>
                thrift.DelegateContents.Neg(TVoid)
            }
          case path: Name.Path =>
            thrift.DelegateContents.PathLeaf(TPath(path.path))
        }
        (thrift.DelegateNode(TPath(path), dentry.show, contents), Map.empty, nextId)
      case DelegateTree.Alt(path, dentry, trees@_*) =>
        val agg = trees.foldLeft(DelegateAltAgg(nextId))(_ + _)
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Alt(agg.trees)), agg.nodes, agg.nextId)
      case DelegateTree.Union(path, dentry, trees@_*) =>
        val agg = trees.foldLeft(DelegateUnionAgg(nextId))(_ + _)
        (thrift.DelegateNode(TPath(path), dentry.show, thrift.DelegateContents.Weighted(agg.trees)), agg.nodes, agg.nextId)
      case DelegateTree.Transformation(path, name, value, tree) =>
        val (node, childNodes, nextNextId) = mkDelegateTree(tree, nextId)
        val bound = value match {
          case bound: Name.Bound =>
            bound.id match {
              case id: Path => thrift.BoundName(TPath(id))
              case _ => thrift.BoundName(TPath(Path.empty))
            }
          case path: Name.Path => thrift.BoundName(TPath(path.path))
        }
        (thrift.DelegateNode(TPath(path), name, thrift.DelegateContents.Transformation(thrift.Transformation(bound, nextNextId))),
          childNodes + (nextNextId -> node), nextNextId + 1)
    }

  def parseDelegateTree(dt: thrift.DelegateTree): DelegateTree[Name.Path] = {
    def parseDelegateNode(node: thrift.DelegateNode): DelegateTree[Name.Path] = {
      node.contents match {
        case thrift.DelegateContents.Error(thrown) =>
          DelegateTree
            .Exception(
              mkPath(node.path.toSeq),
              Dentry.read(dt.root.dentry),
              new Exception(thrown)
            )
        case thrift.DelegateContents.Empty(_) =>
          DelegateTree.Empty(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry))
        case thrift.DelegateContents.Fail(_) =>
          DelegateTree.Fail(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry))
        case thrift.DelegateContents.Neg(_) =>
          DelegateTree.Neg(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry))
        case thrift.DelegateContents.Delegate(child) =>
          DelegateTree.Delegate(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry), parseDelegateNode(dt.nodes(child)))
        case thrift.DelegateContents.PathLeaf(path) =>
          DelegateTree.Leaf(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry), Name.Path(mkPath(path)))
        case thrift.DelegateContents.Alt(children) =>
          val alts = children.map(dt.nodes).map(parseDelegateNode)
          DelegateTree.Alt(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry), alts: _*)
        case thrift.DelegateContents.Weighted(children) =>
          val weights = children.map { child =>
            DelegateTree.Weighted(child.weight, parseDelegateNode(dt.nodes(child.id)))
          }
          DelegateTree.Union(mkPath(node.path.toSeq), Dentry.read(dt.root.dentry), weights: _*)
        case thrift.DelegateContents.BoundLeaf(leaf) =>
          throw new IllegalArgumentException("delegation cannot accept bound names")
        case thrift.DelegateContents.UnknownUnionField(_) =>
          throw new IllegalArgumentException("unknown union field")
        case thrift.DelegateContents.Transformation(transformation) =>
          throw new IllegalArgumentException("delegation cannot accept transformations")
      }
    }
    parseDelegateNode(dt.root)
  }

  private val DefaultNamer: (Path, Namer) = Path.empty -> Namer.global

  case class Capacity(
    bindingCacheActive: Int,
    bindingCacheInactive: Int,
    bindingCacheInactiveTTLSecs: Int,
    addrCacheActive: Int,
    addrCacheInactive: Int,
    addrCacheInactiveTTLSecs: Int
  )

  object Capacity {
    def default = Capacity(
      bindingCacheActive = 1000,
      bindingCacheInactive = 100,
      bindingCacheInactiveTTLSecs = 10.minutes.inSeconds,
      addrCacheActive = 1000,
      addrCacheInactive = 100,
      addrCacheInactiveTTLSecs = 10.minutes.inSeconds
    )
  }
}

/**
 * Exposes a polling interface to Namers.
 */
class ThriftNamerInterface(
  interpreters: Ns => NameInterpreter,
  namers: Map[Path, Namer],
  stamper: ThriftNamerInterface.Stamper,
  capacity: Capacity,
  stats: StatsReceiver
) extends thrift.Namer.MethodPerEndpoint {
  import ThriftNamerInterface._

  private[this] val log = Logger.get(getClass.getName)

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
    mkPath(reqName.toSeq) match {
      case Path.empty =>
        Trace.recordBinary("namerd.srv/bind.err", "empty path")
        val failure = thrift.BindFailure("empty path", 0, ref, ns)
        Future.exception(failure)

      case path =>
        Trace.recordBinary("namerd.srv/bind.ns", ns)
        Trace.recordBinary("namerd.srv/bind.path", path.show)

        Future.const(bindingCache.get(ns, dtab, path)).flatMap { bindingObserver =>
          bindingObserver(reqStamp)
        }.transform {
          case Return((TStamp(tstamp), nameTree)) =>
            Trace.recordBinary("namerd.srv/bind.tree", nameTree.show)
            val (root, nodes, _) = mkTree(nameTree)
            Future.value(thrift.Bound(tstamp, thrift.BoundTree(root, nodes), ns))

          case Throw(e: ClientDiscardedRequestException) =>
            // Don't log interruptions.
            val failure = thrift.BindFailure(e.getMessage, 0, ref, ns)
            Future.exception(failure)

          case Throw(e) =>
            Trace.recordBinary("namerd.srv/bind.fail", e.toString)
            log.error(e, "binding name %s", path.show)
            val failure = thrift.BindFailure(e.getMessage, 0, ref, ns)
            Future.exception(failure)
        }
    }
  }

  private[this] def observeBind(ns: Ns, dtab: Dtab, path: Path) =
    mkObserver(interpreters(ns).bind(dtab, path), stamper)
  private[this] val bindingCache = new ObserverCache[(String, Dtab, Path), NameTree[Name.Bound]](
    activeCapacity = capacity.bindingCacheActive,
    inactiveCapacity = capacity.bindingCacheInactive,
    inactiveTTLSecs = capacity.bindingCacheInactiveTTLSecs,
    stats = stats.scope("bindingcache"),
    mkObserver = (observeBind _).tupled
  )

  /**
   * Converts Addr.Metadata into Thrift AddrMeta
   */
  private[this] def convertMeta(scalaMeta: Addr.Metadata): Option[thrift.AddrMeta] = {
    // TODO translate metadata (latency compensation, etc)

    val authority = scalaMeta.get(Metadata.authority).map(_.toString)
    val nodeName = scalaMeta.get(Metadata.nodeName).map(_.toString)
    val weight = scalaMeta.get(Metadata.endpointWeight)
      .flatMap(w => Try(w.toString.toDouble).toOption)

    if (authority.isDefined || nodeName.isDefined)
      Some(thrift.AddrMeta(
        authority = authority,
        nodeName = nodeName,
        endpointAddrWeight = weight
      ))
    else
      None
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
    mkPath(reqName.toSeq) match {
      case Path.empty =>
        Trace.recordBinary("namerd.srv/addr.err", "empty path")
        val failure = thrift.AddrFailure("empty path", 0, ref)
        Future.exception(failure)

      case fullPath =>
        val path = Paths.stripTransformerPrefix(fullPath)
        Trace.recordBinary("namerd.srv/addr.path", path.show)
        Future.const(addrCache.get(path)).flatMap { addrObserver =>
          addrObserver(reqStamp)
        }.transform {
          case Return((newStamp, None)) =>
            Trace.recordBinary("namerd.srv/addr.result", "neg")
            val addr = thrift.Addr(TStamp(newStamp), thrift.AddrVal.Neg(TVoid))
            Future.value(addr)

          case Return((newStamp, Some(bound@Addr.Bound(addrs, boundMeta)))) =>
            Trace.recordBinary("namerd.srv/addr.result", bound.toString)
            val taddrs = addrs.collect {
              case Address.Inet(isa, addressMeta) =>
                val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
                thrift.TransportAddress(ip, isa.getPort, convertMeta(addressMeta))
            }
            val addr = thrift.Addr(
              TStamp(newStamp),
              thrift.AddrVal.Bound(thrift.BoundAddr(taddrs, convertMeta(boundMeta)))
            )
            Future.value(addr)

          case Throw(e: ClientDiscardedRequestException) =>
            // Don't log interruptions.
            val failure = thrift.AddrFailure(e.getMessage, 0, ref)
            Future.exception(failure)

          case Throw(NonFatal(e)) =>
            Trace.recordBinary("namerd.srv/addr.fail", e.toString)
            log.error(e, "resolving addr %s", path.show)
            val failure = thrift.AddrFailure(e.getMessage, 0, ref)
            Future.exception(failure)

          case Throw(e) =>
            Trace.recordBinary("namerd.srv/addr.fail", e.toString)
            log.error(e, "resolving addr %s", path.show)
            Future.exception(e)
        }
    }
  }

  private[this] def observeAddr(id: Path) = {
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
    AddrObserver(resolution, stamper)
  }
  private[this] val addrCache = new ObserverCache[Path, Option[Addr.Bound]](
    activeCapacity = capacity.addrCacheActive,
    inactiveCapacity = capacity.addrCacheInactive,
    inactiveTTLSecs = capacity.addrCacheInactiveTTLSecs,
    stats = stats.scope("addrcache"),
    mkObserver = observeAddr
  )

  private[this] def bindAddrId(id: Path): Activity[NameTree[Name.Bound]] = {
    val (pfx, namer) = namers.find { case (p, _) => id.startsWith(p) }.getOrElse(DefaultNamer)
    namer.bind(NameTree.Leaf(id.drop(pfx.size)))
  }

  private[this] def nonDelegatorException(ns: String) =
    Future.exception(new UnsupportedOperationException(s"Name Interpreter for $ns cannot show delegations"))

  // Unlike other methods in this interface, delegate is NOT long-polling.  Clients should always
  // request with an empty stamp and should only re-poll when they want to explicitly check for a
  // new value.  For backwards compatibility with older long-polling clients, delegate will never
  // respond to a request with a non-empty stamp.  This means that it will leave the long-poll
  // pending until it is cancelled by the client.
  def delegate(req: thrift.DelegateReq): Future[Delegation] = {
    val thrift.DelegateReq(dtabstr, thrift.Delegation(reqStamp, ttree, ns), _) = req
    val dtab = Dtab.read(dtabstr)
    val tree = parseDelegateTree(ttree).toNameTree

    val validateStamp = if (reqStamp == TStamp.empty) {
      Future.Unit
    } else {
      // A non-empty stamp indicates that this is a subsequent request from a long-polling client.
      // For backwards compatibility, we do not respond so that the client will keep the initial
      // value for the delegation.
      Future.never
    }
    val delegator = validateStamp.before {
      interpreters(ns) match {
        case d: Delegator => Future.value(d)
        case _ => nonDelegatorException(ns)
      }
    }
    val delegation = delegator.flatMap { _.delegate(dtab, tree) }
    delegation.transform {
      case Return(delegateTree) =>
        val (root, nodes, _) = mkDelegateTree(delegateTree)
        // Clients should ignore the stamp value in the response.  However, older long-polling
        // clients will make a second response with this stamp.  We set the stamp to a non-empty
        // value so that we know to leave the second request pending.
        Future.value(thrift.Delegation(TStamp.one, thrift.DelegateTree(root, nodes), ns))
      case Throw(e) =>
        val failure = thrift.DelegationFailure(e.getMessage)
        Future.exception(failure)
    }
  }

  private[this] def observeDtab(ns: Ns) = {
    val act = interpreters(ns) match {
      case interpreter: Delegator =>
        interpreter.dtab
      case _ =>
        throw new UnsupportedOperationException(s"Name Interpreter for $ns cannot show dtab")
    }
    mkObserver(act, stamper)
  }
  private[this] val dtabCache = new ObserverCache[String, Dtab](
    activeCapacity = 10,
    inactiveCapacity = 1,
    inactiveTTLSecs = 10.minutes.inSeconds,
    stats = stats.scope("delegationcache"),
    mkObserver = observeDtab
  )

  def dtab(req: DtabReq): Future[DtabRef] = {
    val thrift.DtabReq(reqStamp, ns, _) = req
    Future.const(dtabCache.get(ns)).flatMap { observer =>
      observer(reqStamp)
    }.transform {
      case Return((TStamp(tstamp), dtab)) =>
        Future.value(thrift.DtabRef(tstamp, dtab.show))
      case Throw(e) =>
        val failure = thrift.DtabFailure(e.getMessage)
        Future.exception(failure)
    }
  }
}
