package io.buoyant.interpreter.mesh

import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{NonFatal => _, _}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.namer.{ConfiguredDtabNamer, Delegator, DelegateTree, Metadata}
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
  ): NameInterpreter with Delegator = {
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
  ) extends NameInterpreter with Delegator {

    /**
     * When observed, streams bound trees from the mesh Interpreter.
     *
     * Leaf nodes include a Var[Addr] that, when observed, streams
     * replica resolutions from the mesh Resolver.
     */
    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
      val open = () => interpreter.streamBoundTree(mkBindReq(root, path, dtab))
      streamActivity(open, decodeBoundTree, backoffs, timer)
    }

    /**
     * When observed, streams dtabs from the mesh server and
     */
    override lazy val dtab: Activity[Dtab] = {
      val open = () => delegator.streamDtab(mkDtabReq(root))
      streamActivity(open, decodeDtab, backoffs, timer)
    }

    override def delegate(
      dtab: Dtab,
      tree: NameTree[Name.Path]
    ): Activity[DelegateTree[Name.Bound]] = {
      val open = () => delegator.streamDelegateTree(mkDelegateTreeReq(root, dtab, tree))
      streamActivity(open, decodeDelegateTree, backoffs, timer)
    }

    private[this] val resolve: Path => Var[Addr] = {
      case Path.empty => Var.value(Addr.Failed("empty"))
      case id =>
        val open = () => resolver.streamReplicas(mkReplicasReq(id))
        streamVar(Addr.Pending, open, replicasToAddr, backoffs, timer)
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
  }

  /**
   * Constructs a Var that, when observed, calls `open()` to obtain a
   * gRPC stream, publishing updates into the Var's state.
   *
   * When the stream returns an error, the Var may be updated (as
   * according to `toT`) and no further updates will will be
   * published.
   */
  private[this] def streamVar[S, T](
    init: T,
    open: () => Stream[S],
    toT: Try[S] => Option[T],
    backoffs0: scala.Stream[Duration],
    timer: Timer
  ): Var[T] = Var.async[T](init) { state =>
    implicit val timer0 = timer

    // As we receive streamed messages, we are careful not to release
    // them until the state has been updated to a new value. This is
    // intended to (1) integrate tightly with flow control and more
    // importantly (2) later integrate with netty's reference counted
    // bueffers.
    @volatile var closed = false
    def loop(
      rsps: Stream[S],
      backoffs: scala.Stream[Duration],
      releasePrior: () => Future[Unit]
    ): Future[Unit] =
      if (closed) releasePrior().rescue(_rescueUnit)
      else rsps.recv().transform {
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

    val f = loop(open(), backoffs0, _releaseNop)
    Closable.make { _ =>
      closed = true
      f.raise(Failure("closed", Failure.Interrupted))
      f
    }
  }

  /**
   * Constructs an Activity that, when observed, calls `open()` to obtain a
   * gRPC stream, publishing updates into the Activity's state.
   *
   * When the stream returns an error, the state is updated to
   * Activity.Failed and no further updates will will be published.
   */
  private[this] def streamActivity[S, T](
    open: () => Stream[S],
    toT: S => Option[T],
    bos: scala.Stream[Duration],
    timer: Timer
  ): Activity[T] = {
    val toState: Try[S] => Option[Activity.State[T]] = {
      case Throw(e) => Some(Activity.Failed(e))
      case Return(s) =>
        toT(s) match {
          case None => None
          case Some(t) => Some(Activity.Ok(t))
        }
    }
    Activity(streamVar(Activity.Pending, open, toState, bos, timer))
  }

  private[this] val decodeDtab: mesh.DtabRsp => Option[Dtab] = {
    case mesh.DtabRsp(Some(mesh.VersionedDtab(_, Some(pdtab)))) =>
      Some(fromDtab(pdtab))
    case _ => None
  }

  private[this] val _collectFromEndpoint: PartialFunction[mesh.Endpoint, Address] = {
    case mesh.Endpoint(Some(_), Some(ipBuf), Some(port), pmeta) =>
      val ipBytes = Buf.ByteArray.Owned.extract(ipBuf)
      val ip = InetAddress.getByAddress(ipBytes)
      val meta = Seq.empty[(String, Any)] ++
        pmeta.flatMap(_.nodeName).map(Metadata.nodeName -> _)
      Address.Inet(new InetSocketAddress(ip, port), Addr.Metadata(meta: _*))
  }

  private[this] val fromReplicas: mesh.Replicas => Addr = {
    case mesh.Replicas(None) => Addr.Neg
    case mesh.Replicas(Some(result)) => result match {
      case mesh.Replicas.OneofResult.Pending(_) => Addr.Pending
      case mesh.Replicas.OneofResult.Neg(_) => Addr.Neg
      case mesh.Replicas.OneofResult.Failed(mesh.Replicas.Failed(msg)) =>
        Addr.Failed(msg.getOrElse("unknown"))

      case mesh.Replicas.OneofResult.Bound(mesh.Replicas.Bound(paddrs)) =>
        Addr.Bound(paddrs.collect(_collectFromEndpoint).toSet)
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
