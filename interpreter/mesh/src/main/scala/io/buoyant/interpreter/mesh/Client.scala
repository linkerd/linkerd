package io.buoyant.interpreter.mesh

import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.buoyant.h2.Reset
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.grpc.runtime.Stream
import io.buoyant.namer.{DelegateTree, Delegator, InstrumentedActivity, InstrumentedVar}
import io.linkerd.mesh
import io.linkerd.mesh.Converters._
import java.net.{InetAddress, InetSocketAddress}
import scala.util.control.{NoStackTrace, NonFatal}

object Client {

  def apply(
    root: Path,
    service: Service[h2.Request, h2.Response],
    backoffs: scala.Stream[Duration],
    timer: Timer
  ): NameInterpreter with Delegator with Admin.WithHandlers = {
    val interpreter = new mesh.Interpreter.Client(service)
    val resolver = new mesh.Resolver.Client(service)
    val delegator = new mesh.Delegator.Client(service)
    new Impl(root, interpreter, resolver, delegator, backoffs, timer)
  }

  def apply(
    root: Path,
    interpreter: mesh.Interpreter,
    resolver: mesh.Resolver,
    delegator: mesh.Delegator,
    backoffs: scala.Stream[Duration],
    timer: Timer
  ): NameInterpreter with Delegator =
    new Impl(root, interpreter, resolver, delegator, backoffs, timer)

  private[this] val _releaseNop = () => Future.Unit
  private[this] val _rescueUnit: PartialFunction[Throwable, Future[Unit]] = {
    case NonFatal(_) => Future.Unit
  }
  private[this] class Impl(
    root: Path,
    interpreter: mesh.Interpreter,
    resolver: mesh.Resolver,
    delegator: mesh.Delegator,
    backoffs: scala.Stream[Duration],
    timer: Timer
  ) extends NameInterpreter with Delegator with Admin.WithHandlers {

    private case class InstrumentedBind(
      act: InstrumentedActivity[NameTree[Name.Bound]],
      stream: StreamState[mesh.BindReq, mesh.BoundTreeRsp]
    )
    private case class InstrumentedResolve(
      act: InstrumentedVar[Addr],
      stream: StreamState[mesh.ReplicasReq, mesh.Replicas]
    )
    private case class InstrumentedDtab(
      act: InstrumentedActivity[Dtab],
      stream: StreamState[mesh.DtabReq, mesh.DtabRsp]
    )

    private[this] val bindCacheMu = new {}
    @volatile private[this] var bindCache = Map.empty[(Dtab, Path), InstrumentedBind]

    private[this] val resolveCacheMu = new {}
    @volatile private[this] var resolveCache = Map.empty[Path, InstrumentedResolve]

    private[this] val dtabCacheMu = new {}
    @volatile private[this] var dtabCache: InstrumentedDtab = null

    /**
     * When observed, streams bound trees from the mesh Interpreter.
     *
     * Leaf nodes include a Var[Addr] that, when observed, streams
     * replica resolutions from the mesh Resolver.
     */
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
      val key = (dtab, path)
      bindCacheMu.synchronized {
        bindCache.get(key) match {
          case Some(bind) => bind.act.underlying
          case None =>
            val streamState = new StreamState[mesh.BindReq, mesh.BoundTreeRsp]
            val open = () => {
              val req = mkBindReq(root, path, dtab)
              streamState.recordApiCall(req)
              interpreter.streamBoundTree(req)
            }
            val bind = streamActivity(open, decodeBoundTree, backoffs, timer, streamState)
            bindCache += (key -> InstrumentedBind(bind, streamState))
            bind.underlying
        }
      }
    }

    /**
     * When observed, streams dtabs from the mesh server.
     */
    override lazy val dtab: Activity[Dtab] = {
      dtabCacheMu.synchronized {
        if (dtabCache != null) {
          dtabCache.act.underlying
        } else {
          val streamState = new StreamState[mesh.DtabReq, mesh.DtabRsp]
          val open = () => {
            val req = mkDtabReq(root)
            streamState.recordApiCall(req)
            delegator.streamDtab(req)
          }
          streamActivity(open, decodeDtab, backoffs, timer, streamState).underlying
        }
      }
    }

    override def delegate(
      dtab: Dtab,
      tree: NameTree[Name.Path]
    ): Future[DelegateTree[Name.Bound]] = {
      val req = mkDelegateTreeReq(root, dtab, tree)
      delegator.getDelegateTree(req).flatMap { delegateTree =>
        decodeDelegateTree(delegateTree) match {
          case Some(decoded) => Future.value(decoded)
          case None => Future.exception(new Exception("Failed to decode delegate tree: " + delegateTree))
        }
      }
    }

    private[this] val resolve: Path => Var[Addr] = {
      case Path.empty => Var.value(Addr.Failed("empty"))
      case id =>
        resolveCacheMu.synchronized {
          resolveCache.get(id) match {
            case Some(resolution) => resolution.act.underlying
            case None =>
              val streamState = new StreamState[mesh.ReplicasReq, mesh.Replicas]
              val open = () => {
                val req = mkReplicasReq(id)
                streamState.recordApiCall(req)
                resolver.streamReplicas(req)
              }
              val resolution = streamVar(Addr.Pending, open, replicasToAddr, backoffs, timer, streamState)
              resolveCache += (id -> InstrumentedResolve(resolution, streamState))
              resolution.underlying
          }
        }
    }

    private[this] val fromBoundNameTree: mesh.BoundNameTree => NameTree[Name.Bound] =
      mkFromBoundNameTree(resolve)

    private[this] val decodeBoundTree: mesh.BoundTreeRsp => Option[NameTree[Name.Bound]] = {
      case mesh.BoundTreeRsp(Some(ptree)) => Some(fromBoundNameTree(ptree))
      case _ => None
    }

    private[this] val fromBoundDelegateTree: mesh.BoundDelegateTree => DelegateTree[Name.Bound] =
      mkFromBoundDelegateTree(resolve)

    private[this] val decodeDelegateTree: mesh.DelegateTreeRsp => Option[DelegateTree[Name.Bound]] = {
      case mesh.DelegateTreeRsp(Some(ptree)) => Some(fromBoundDelegateTree(ptree))
      case _ => None
    }

    override def adminHandlers: Seq[Admin.Handler] = Seq(
      Admin.Handler(
        s"/interpreter_state/io.l5d.mesh${root.show}.json",
        new ClientStateHandler
      )
    )

    class ClientStateHandler extends Service[Request, Response] {

      private[this] val mapper = Parser.jsonObjectMapper(Nil)

      override def apply(request: Request): Future[Response] = {
        val binds = bindCache
        val resolves = resolveCache
        val dtab = dtabCache

        val bindState = binds.groupBy {
          case ((dtab, path), bind) => path
        }.map {
          case (path, binds) =>
            path.show -> binds.map {
              case ((dtab, _), InstrumentedBind(act, stream)) =>
                val snapshot = act.stateSnapshot().map { treeOpt =>
                  treeOpt.map(_.show)
                }
                dtab.show -> Map(
                  "state" -> snapshot,
                  "watch" -> stream
                )
            }
        }
        val resolveState = resolves.map {
          case (path, InstrumentedResolve(act, stream)) =>
            path.show -> Map(
              "state" -> act.stateSnapshot(),
              "watch" -> stream
            )
        }
        val dtabState = Option(dtab).map { dtab =>
          "dtab" -> Map(
            "state" -> dtab.act.stateSnapshot(),
            "watch" -> dtab.stream
          )
        }
        val state = Map(
          "bind" -> bindState,
          "resolve" -> resolveState
        ) ++ dtabState

        val json = mapper.writeValueAsString(state)
        val res = Response()
        res.mediaType = MediaType.Json
        res.contentString = json
        Future.value(res)
      }
    }
  }

  /**
   * Constructs a Var that, when observed, calls `open()` to obtain a
   * gRPC stream, publishing updates into the Var's state.
   *
   * When the stream returns an error, the Var may be updated (as
   * according to `toT`) and no further updates will be published.
   */
  private[this] def streamVar[S, T](
    init: T,
    open: () => Stream[S],
    toT: Try[S] => Option[T],
    backoffs0: scala.Stream[Duration],
    timer: Timer,
    streamState: StreamState[_, S]
  ): InstrumentedVar[T] = InstrumentedVar[T](init) { state =>
    implicit val timer0 = timer

    // As we receive streamed messages, we are careful not to release
    // them until the state has been updated to a new value. This is
    // intended to (1) integrate tightly with flow control and more
    // importantly (2) later integrate with netty's reference counted
    // bueffers.
    @volatile var closed = false
    @volatile var currentStream: Stream[S] = null
    def loop(
      rsps: Stream[S],
      backoffs: scala.Stream[Duration],
      releasePrior: () => Future[Unit]
    ): Future[Unit] = {
      currentStream = rsps
      if (closed) releasePrior().rescue(_rescueUnit)
      else rsps.recv().respond { rep =>
        streamState.recordResponse(rep.map(_.value))
      }.transform {
        case Throw(_) if closed =>
          releasePrior().rescue(_rescueUnit)

        case Throw(NonFatal(e)) =>
          val releasePriorNoError = () => releasePrior().rescue(_rescueUnit)
          backoffs match {
            case wait #:: moreBackoffs =>
              Future.sleep(wait).before(loop(open(), moreBackoffs, releasePriorNoError))

            case _ => // empty: fail
              releasePriorNoError().before(Future.exception(e))
          }

        case Throw(e) => // fatal
          Future.exception(e)

        case Return(Stream.Releasable(s, release)) =>
          toT(Return(s)) match {
            case None =>
              release().before(loop(rsps, backoffs0, releasePrior))
            case Some(t) =>
              state() = t
              releasePrior().before(loop(rsps, backoffs0, release))
          }
      }
    }

    val f = loop(open(), backoffs0, _releaseNop)
    Closable.make { _ =>
      closed = true
      if (currentStream != null) {
        currentStream.reset(Reset.Cancel)
      }
      streamState.recordStreamEnd()
      f
    }
  }

  /**
   * Constructs an Activity that, when observed, calls `open()` to obtain a
   * gRPC stream, publishing updates into the Activity's state.
   *
   * When the stream returns an error, the state is updated to
   * Activity.Failed and no further updates will be published.
   */
  private[this] def streamActivity[S, T](
    open: () => Stream[S],
    toT: S => Option[T],
    bos: scala.Stream[Duration],
    timer: Timer,
    state: StreamState[_, S]
  ): InstrumentedActivity[T] = {
    val toState: Try[S] => Option[Activity.State[T]] = {
      case Throw(e) => Some(Activity.Failed(e))
      case Return(s) =>
        toT(s) match {
          case None => None
          case Some(t) => Some(Activity.Ok(t))
        }
    }
    new InstrumentedActivity(streamVar(Activity.Pending, open, toState, bos, timer, state))
  }

  private[this] val decodeDtab: mesh.DtabRsp => Option[Dtab] = {
    case mesh.DtabRsp(Some(mesh.VersionedDtab(_, Some(pdtab)))) =>
      Some(fromDtab(pdtab))
    case _ => None
  }

  private[this] val _collectFromEndpoint: PartialFunction[mesh.Endpoint, Address] = {
    case mesh.Endpoint(Some(_), Some(ipBuf), Some(port), _, metadata) =>
      val ipBytes = Buf.ByteArray.Owned.extract(ipBuf)
      val ip = InetAddress.getByAddress(ipBytes)
      Address
        .Inet(
          new InetSocketAddress(ip, port),
          metadata
        )
  }

  private[this] val fromReplicas: mesh.Replicas => Addr = {
    case mesh.Replicas(None) => Addr.Neg
    case mesh.Replicas(Some(result)) => result match {
      case mesh.Replicas.OneofResult.Pending(_) => Addr.Pending
      case mesh.Replicas.OneofResult.Neg(_) => Addr.Neg
      case mesh.Replicas.OneofResult.Failed(mesh.Replicas.Failed(msg)) =>
        Addr.Failed(msg.getOrElse("unknown"))

      case mesh.Replicas.OneofResult.Bound(mesh.Replicas.Bound(paddrs, metadata)) =>
        Addr
          .Bound(
            paddrs.collect(_collectFromEndpoint).toSet,
            metadata
          )
    }
  }

  private[this] val replicasToAddr: Try[mesh.Replicas] => Option[Addr] = {
    case Throw(e) => Some(Addr.Failed(e))
    case Return(pa) => Some(fromReplicas(pa))
  }

  private[this] def mkFromBoundNameTree(
    resolve: Path => Var[Addr]
  ): mesh.BoundNameTree => NameTree[Name.Bound] = {
    def bindTree(ptree: mesh.BoundNameTree): NameTree[Name.Bound] = {
      ptree.node match {
        case None => throw new IllegalArgumentException("No bound tree")
        case Some(mesh.BoundNameTree.OneofNode.Neg(_)) => NameTree.Neg
        case Some(mesh.BoundNameTree.OneofNode.Fail(_)) => NameTree.Fail
        case Some(mesh.BoundNameTree.OneofNode.Empty(_)) => NameTree.Empty

        case Some(mesh.BoundNameTree.OneofNode.Leaf(mesh.BoundNameTree.Leaf(Some(pid), presidual))) =>
          val id = fromPath(pid)
          val residual = presidual match {
            case Some(p) => fromPath(p)
            case None => Path.empty
          }
          NameTree.Leaf(Name.Bound(resolve(id), id, residual))

        case Some(mesh.BoundNameTree.OneofNode.Alt(mesh.BoundNameTree.Alt(ptrees))) =>
          val trees = ptrees.map(bindTree(_))
          NameTree.Alt(trees: _*)

        case Some(mesh.BoundNameTree.OneofNode.Union(mesh.BoundNameTree.Union(pwtrees))) =>
          val wtrees = pwtrees.collect {
            case mesh.BoundNameTree.Union.Weighted(Some(w), Some(t)) =>
              NameTree.Weighted(w, bindTree(t))
          }
          NameTree.Union(wtrees: _*)

        case Some(tree) => throw new IllegalArgumentException(s"Illegal bound tree: $tree")
      }
    }
    bindTree _
  }

  private[this] def mkFromBoundDelegateTree(
    resolve: Path => Var[Addr]
  ): mesh.BoundDelegateTree => DelegateTree[Name.Bound] = {
    def bindTree(t: mesh.BoundDelegateTree): DelegateTree[Name.Bound] = t match {
      case mesh.BoundDelegateTree(Some(dpath0), Some(dentry0), Some(node)) =>
        val dpath = fromPath(dpath0)
        val dentry = fromDentry(dentry0)
        node match {
          case mesh.BoundDelegateTree.OneofNode.Neg(_) =>
            DelegateTree.Neg(dpath, dentry)
          case mesh.BoundDelegateTree.OneofNode.Fail(_) =>
            DelegateTree.Fail(dpath, dentry)
          case mesh.BoundDelegateTree.OneofNode.Empty(_) =>
            DelegateTree.Empty(dpath, dentry)

          case mesh.BoundDelegateTree.OneofNode.Leaf(mesh.BoundDelegateTree.Leaf(Some(pid), Some(ppath))) =>
            val id = fromPath(pid)
            val path = fromPath(ppath)
            DelegateTree.Leaf(dpath, dentry, Name.Bound(resolve(id), id, path))

          case mesh.BoundDelegateTree.OneofNode.Alt(mesh.BoundDelegateTree.Alt(ptrees)) =>
            val trees = ptrees.map(bindTree)
            DelegateTree.Alt(dpath, dentry, trees: _*)

          case mesh.BoundDelegateTree.OneofNode.Union(mesh.BoundDelegateTree.Union(ptrees)) =>
            val trees = ptrees.collect {
              case mesh.BoundDelegateTree.Union.Weighted(Some(weight), Some(ptree)) =>
                DelegateTree.Weighted(weight, bindTree(ptree))
            }
            DelegateTree.Union(dpath, dentry, trees: _*)

          case mesh.BoundDelegateTree.OneofNode.Delegate(ptree) =>
            DelegateTree.Delegate(dpath, dentry, bindTree(ptree))

          case mesh.BoundDelegateTree.OneofNode.Exception(msg) =>
            DelegateTree.Exception(dpath, dentry, DelegateException(msg))

          case tree =>
            throw new IllegalArgumentException(s"illegal delegate tree node: $node")
        }

      case tree =>
        throw new IllegalArgumentException(s"illegal delegate tree: $tree")
    }

    bindTree _
  }

  private[this] def mkBindReq(root: Path, path: Path, dtab: Dtab) =
    mesh.BindReq(Some(toPath(root)), Some(toPath(path)), Some(toDtab(dtab)))

  private[this] def mkReplicasReq(id: Path) =
    mesh.ReplicasReq(Some(toPath(id)))

  private[this] def mkDelegateTreeReq(root: Path, dtab: Dtab, tree: NameTree[Name.Path]) =
    mesh.DelegateTreeReq(Some(toPath(root)), Some(toPathNameTree(tree.map(_.path))), Some(toDtab(dtab)))

  private[this] def mkDtabReq(root: Path) =
    mesh.DtabReq(Some(toPath(root)))
}

case class DelegateException(msg: String)
  extends Throwable(msg)
  with NoStackTrace
