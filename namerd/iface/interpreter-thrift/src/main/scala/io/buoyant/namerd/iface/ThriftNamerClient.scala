package io.buoyant.namerd.iface

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.namer.{DelegateTree, Delegator, InstrumentedActivity, InstrumentedVar, Metadata}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import java.net.{InetAddress, InetSocketAddress}

class ThriftNamerClient(
  client: thrift.Namer.MethodPerEndpoint,
  namespace: String,
  backoffs: Backoff,
  statsReceiver: StatsReceiver = NullStatsReceiver,
  clientId: Path = Path.empty,
  _timer: Timer = DefaultTimer
) extends NameInterpreter with Delegator with Admin.WithHandlers {
  import ThriftNamerInterface._

  private[this] implicit val log = Logger.get(getClass.getName)
  private[this] implicit val timer = _timer
  private[this] val tclientId = TPath(clientId)

  private[this] val Released = Failure("Released", FailureFlags.Interrupted)

  private case class InstrumentedBind(
    act: InstrumentedActivity[NameTree[Name.Bound]],
    watch: PollState[thrift.BindReq, thrift.Bound]
  )
  private case class InstrumentedAddr(
    act: InstrumentedVar[Addr],
    watch: PollState[thrift.AddrReq, thrift.Addr]
  )

  /*
   * XXX needs proper eviction, etc
   */
  private[this] val bindCacheMu = new {}
  private[this] var bindCache = Map.empty[(Dtab, Path), InstrumentedBind]

  private[this] val addrCacheMu = new {}
  private[this] var addrCache = Map.empty[Path, InstrumentedAddr]

  statsReceiver.addGauge("bindcache.size")(bindCache.size)
  statsReceiver.addGauge("addrcache.size")(addrCache.size)

  private[this] val bindSuccessCounter = statsReceiver.counter("bind", "success")
  private[this] val bindFailureCounter = statsReceiver.counter("bind", "failure")
  private[this] val addrSuccessCounter = statsReceiver.counter("addr", "success")
  private[this] val addrFailureCounter = statsReceiver.counter("addr", "failure")
  private[this] val delegateSuccessCounter = statsReceiver.counter("delegate", "success")
  private[this] val delegateFailureCounter = statsReceiver.counter("delegate", "failure")
  private[this] val dtabSuccessCounter = statsReceiver.counter("dtab", "success")
  private[this] val dtabFailureCounter = statsReceiver.counter("dtab", "failure")

  def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
    Trace.recordBinary("namerd.client/bind.dtab", dtab.show)
    Trace.recordBinary("namerd.client/bind.path", path.show)
    val key = (dtab, path)
    bindCacheMu.synchronized {
      bindCache.get(key) match {
        case Some(bind) =>
          Trace.recordBinary("namerd.client/bind.cached", true)
          bind.act.underlying

        case None =>
          Trace.recordBinary("namerd.client/bind.cached", false)
          val bind = watchName(dtab, path)
          bindCache += (key -> bind)
          bind.act.underlying
      }
    }
  }

  private[this] def watchName(dtab: Dtab, path: Path): InstrumentedBind = {
    val tdtab = dtab.show
    val tpath = TPath(path)

    val state = new PollState[thrift.BindReq, thrift.Bound]

    val act = InstrumentedActivity[NameTree[Name.Bound]] { states =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp, backoffs0: Backoff): Unit = if (!stopped) {
        Trace.recordBinary("namerd.client/bind.ns", namespace)
        Trace.recordBinary("namerd.client/bind.path", path.show)

        val req = thrift.BindReq(tdtab, thrift.NameRef(stamp0, tpath, namespace), tclientId)
        state.recordApiCall(req)
        pending = Trace.letClear(client.bind(req)).respond { rep =>
          state.recordResponse(rep)
          rep match {
            case Return(thrift.Bound(stamp1, ttree, _)) =>
              bindSuccessCounter.incr()
              states() = Try(mkTree(ttree)) match {
                case Return(tree) =>
                  Trace.recordBinary("namerd.client/bind.tree", tree.show)
                  Activity.Ok(tree)
                case Throw(e) =>
                  Trace.recordBinary("namerd.client/bind.err", e.toString)
                  Activity.Failed(e)
              }
              loop(stamp1, backoffs0)

            case Throw(e@thrift.BindFailure(reason, _, _, _)) =>
              bindFailureCounter.incr()
              Trace.recordBinary("namerd.client/bind.fail", reason)
              if (!stopped) {
                pending = Future.sleep(backoffs0.duration)
                  .onSuccess(_ => loop(stamp0, backoffs0.next))
              }

            case Throw(e: Failure) if e.isFlagged(FailureFlags.Interrupted) =>
            // The request has been cancelled.  Do nothing.

            case Throw(e) =>
              bindFailureCounter.incr()
              log.error(e, "bind %s", path.show)
              Trace.recordBinary("namerd.client/bind.exc", e.toString)
              pending = Future.sleep(backoffs0.duration)
                .onSuccess(_ => loop(TStamp.empty, backoffs0.next))
          }
        }
      }

      loop(TStamp.empty, backoffs)
      Closable.make { deadline =>
        log.debug("bind released %s", path.show)
        stopped = true
        pending.raise(Released)
        Future.Unit
      }
    }

    InstrumentedBind(act, state)
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
        NameTree.Leaf(Name.Bound(addr.act.underlying, id, residual))

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

  /**
   * Converts Thrift AddrMeta into Addr.Metadata
   */
  private[this] def convertMeta(thriftMeta: Option[thrift.AddrMeta]): Addr.Metadata = {
    val authority = thriftMeta.flatMap(_.authority).map(Metadata.authority -> _)
    val nodeName = thriftMeta.flatMap(_.nodeName).map(Metadata.nodeName -> _)
    val weight = thriftMeta.flatMap(_.endpointAddrWeight).map(Metadata.endpointWeight -> _)
    (authority ++ nodeName ++ weight).toMap
  }

  private[this] def watchAddr(id: TPath): InstrumentedAddr = {
    val idPath = mkPath(id).show

    val state = new PollState[thrift.AddrReq, thrift.Addr]

    val ivar = InstrumentedVar[Addr](Addr.Pending) { addr =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp, backoffs0: Backoff): Unit = if (!stopped) {
        Trace.recordBinary("namerd.client/addr.path", idPath)
        val req = thrift
          .AddrReq(thrift.NameRef(stamp0, id, namespace), tclientId)
        state.recordApiCall(req)
        pending = Trace.letClear(client.addr(req)).respond { rep =>
          state.recordResponse(rep)
          rep match {
            case Return(thrift.Addr(stamp1, thrift.AddrVal.Neg(_))) =>
              addr() = Addr.Neg
              Trace.record("namerd.client/addr.neg")
              loop(stamp1, backoffs0)

            case Return(thrift.Addr(stamp1, thrift.AddrVal.Bound(thrift.BoundAddr(taddrs, boundMeta)))) =>
              addrSuccessCounter.incr()
              val addrs = taddrs.map { taddr =>
                val thrift.TransportAddress(ipbb, port, addressMeta) = taddr
                val ipBytes = Buf.ByteArray.Owned
                  .extract(Buf.ByteBuffer.Owned(ipbb))
                val ip = InetAddress.getByAddress(ipBytes)
                Address.Inet(
                  new InetSocketAddress(ip, port),
                  convertMeta(addressMeta)
                )
              }
              // TODO convert metadata
              Trace.recordBinary("namerd.client/addr.bound", addrs)
              addr() = Addr.Bound(addrs.toSet[Address], convertMeta(boundMeta))
              loop(stamp1, backoffs0)

            case Throw(e@thrift.AddrFailure(msg, _, _)) =>
              addrFailureCounter.incr()
              Trace.recordBinary("namerd.client/addr.fail", msg)
              if (!stopped) {
                pending = Future.sleep(backoffs0.duration)
                  .onSuccess(_ => loop(stamp0, backoffs0.next))
              }

            case Throw(e: Failure) if e.isFlagged(FailureFlags.Interrupted) =>
            // The request has been cancelled.  Do nothing.

            case Throw(e) =>
              addrFailureCounter.incr()
              log.error(e, "addr on %s", idPath)
              Trace.recordBinary("namerd.client/addr.exc", e.getMessage)
              pending = Future.sleep(backoffs0.duration)
                .onSuccess(_ => loop(TStamp.empty, backoffs0.next))
          }
        }
      }

      loop(TStamp.empty, backoffs)
      Closable.make { deadline =>
        log.debug("addr released %s", idPath)
        stopped = true
        pending.raise(Released)
        Future.Unit
      }
    }
    InstrumentedAddr(ivar, state)
  }

  private[this] def mkDelegateTree(dt: thrift.DelegateTree): DelegateTree[Name.Bound] = {
    def mk(node: thrift.DelegateNode): DelegateTree[Name.Bound] = {
      node.contents match {
        case thrift.DelegateContents.Error(thrown) =>
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
          val bound = Name.Bound(addr.act.underlying, id, residual)
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
    tree: NameTree[Name.Path]
  ): Future[DelegateTree[Bound]] = {
    val tdtab = dtab.show
    val (root, nodes, _) = tree match {
      case NameTree.Leaf(n@Name.Path(p)) =>
        ThriftNamerInterface.mkDelegateTree(DelegateTree.Leaf(p, Dentry.nop, n))
      case _ => throw new IllegalArgumentException("Delegation too complex")
    }
    val ttree = thrift.DelegateTree(root, nodes)

    val req = thrift.DelegateReq(tdtab, thrift.Delegation(TStamp.empty, ttree, namespace), tclientId)
    Trace.letClear(client.delegate(req)).transform {
      case Return(thrift.Delegation(stamp1, ttree, _)) =>
        delegateSuccessCounter.incr()
        Future(mkDelegateTree(ttree))
      case Throw(e@thrift.DelegationFailure(reason)) =>
        delegateFailureCounter.incr()
        log.error("delegation failed: %s", reason)
        Future.exception(e)
      case Throw(e) =>
        delegateFailureCounter.incr()
        log.error(e, "delegation failed")
        Future.exception(e)
    }
  }

  override def dtab: Activity[Dtab] = {

    val states = Var.async[Activity.State[Dtab]](Activity.Pending) { states =>
      @volatile var stopped = false
      @volatile var pending: Future[_] = Future.Unit

      def loop(stamp0: TStamp, backoffs0: Backoff): Unit = if (!stopped) {

        val req = thrift.DtabReq(stamp0, namespace, tclientId)
        pending = Trace.letClear(client.dtab(req)).respond {
          case Return(thrift.DtabRef(stamp1, dtab)) =>
            dtabSuccessCounter.incr()
            states() = Try(Dtab.read(dtab)) match {
              case Return(dtab) =>
                Activity.Ok(dtab)
              case Throw(e) =>
                Activity.Failed(e)
            }
            loop(stamp1, backoffs0)

          case Throw(e@thrift.DtabFailure(reason)) =>
            dtabFailureCounter.incr()
            log.error("dtab %s lookup failed: %s", namespace, reason)
            states() = Activity.Failed(e)

          case Throw(e: Failure) if e.isFlagged(FailureFlags.Interrupted) =>
          // The request has been cancelled.  Do nothing.

          case Throw(e) =>
            dtabFailureCounter.incr()
            log.error(e, "dtab %s lookup failed", namespace)
            pending = Future.sleep(backoffs0.duration).onSuccess(_ => loop(TStamp.empty, backoffs0.next))
        }
      }

      loop(TStamp.empty, backoffs)
      Closable.make { deadline =>
        log.debug("dtab %s released", namespace)
        stopped = true
        pending.raise(Released)
        Future.Unit
      }
    }

    Activity(states)
  }

  override def adminHandlers: Seq[Admin.Handler] = Seq(
    Admin.Handler(
      s"/interpreter_state/io.l5d.namerd/$namespace.json",
      new NamerClientStateHandler(
        bindCache, addrCache
      )
    )
  )

  class NamerClientStateHandler(
    getBindCache: => Map[(Dtab, Path), InstrumentedBind],
    getAddrCache: => Map[Path, InstrumentedAddr]
  ) extends Service[Request, Response] {

    private[this] val mapper = Parser.jsonObjectMapper(Nil)

    override def apply(request: Request): Future[Response] = {

      val bindState = getBindCache.groupBy {
        case ((dtab, path), bind) => path
      }.map {
        case (path, binds) =>
          path.show -> binds.map {
            case ((dtab, _), InstrumentedBind(act, poll)) =>
              val snapshot = act.stateSnapshot().map { treeOpt =>
                treeOpt.map { tree =>
                  tree.map { bound =>
                    Map("id" -> bound.id, "path" -> bound.path)
                  }
                }
              }
              dtab.show -> Map(
                "state" -> snapshot,
                "poll" -> poll
              )
          }
      }
      val addrState = getAddrCache.map {
        case (path, InstrumentedAddr(act, poll)) =>
          path.show -> Map(
            "state" -> act.stateSnapshot(),
            "poll" -> poll
          )
      }

      val state = Map(
        "bind" -> bindState,
        "addr" -> addrState
      )
      val json = mapper.writeValueAsString(state)

      val res = Response()
      res.mediaType = MediaType.Json
      res.contentString = json
      Future.value(res)
    }
  }
}
