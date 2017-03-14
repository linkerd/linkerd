package io.buoyant.namerd
package iface.grpc

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Closable, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.namer.{ConfiguredDtabNamer, Delegator, DelegateTree}
import io.buoyant.proto.{BoundNameTree, BoundDelegateTree}
import io.buoyant.proto.namerd.{Interpreter => ProtoInterpreter, Addr => _, _}
import scala.util.control.NoStackTrace

object InterpreterClient {
  import InterpreterProto._

  def apply(client: ProtoInterpreter, namespace: String): NameInterpreter =
    Impl(client, namespace)

  case class Impl(client: ProtoInterpreter, namespace: String)
    extends NameInterpreter with Delegator {

    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
      val req = mkBindReq(namespace, path, dtab)
      val run = Var.async[Activity.State[NameTree[Name.Bound]]](Activity.Pending) { state =>
        val trees = client.streamBoundTree(req)

        @volatile var closed = false
        def loop(): Future[Unit] =
          if (closed) Future.Unit
          else trees.recv().transform {
            case Throw(e) =>
              state() = Activity.Failed(e)
              Future.exception(e)

            case Return(Stream.Releasable(BoundTreeRsp(ptree), release)) =>
              ptree match {
                case None =>
                case Some(ptree) =>
                  state() = Activity.Ok(fromProtoBoundNameTree(ptree))
              }
              // XXX shouldn't release until this state is changed
              // later, but deferring this could currently break
              // flow-control.
              release().before(loop())
          }

        val f = loop()
        Closable.make { _ =>
          closed = true
          f.raise(Failure("closed", Failure.Interrupted))
          trees.reset(GrpcStatus.Ok())
          Future.Unit
        }
      }
      Activity(run)
    }

    override lazy val dtab: Activity[Dtab] = {
      val req = mkDtabReq(namespace)
      val run = Var.async[Activity.State[Dtab]](Activity.Pending) { state =>
        val dtabs = client.streamDtab(req)

        @volatile var closed = false
        def loop(): Future[Unit] =
          if (closed) Future.Unit
          else dtabs.recv().transform {
            case Throw(e) =>
              state() = Activity.Failed(e)
              Future.exception(e)

            case Return(Stream.Releasable(DtabRsp(vd), release)) =>
              vd match {
                case Some(VersionedDtab(_, Some(pdtab))) =>
                  state() = Activity.Ok(fromProtoDtab(pdtab))
                case _ =>
              }
              // XXX shouldn't release until this state is changed
              // later, but deferring this could currently break
              // flow-control.
              release().before(loop())
          }

        val f = loop()
        Closable.make { _ =>
          closed = true
          f.raise(Failure("closed", Failure.Interrupted))
          dtabs.reset(GrpcStatus.Ok())
          Future.Unit
        }
      }
      Activity(run)
    }

    override def delegate(
      dtab: Dtab,
      tree: NameTree[Name.Path]
    ): Activity[DelegateTree[Name.Bound]] = {
      val req = mkDelegateTreeReq(namespace, dtab, tree)

      val run = Var.async[Activity.State[DelegateTree[Name.Bound]]](Activity.Pending) { state =>
        val trees = client.streamDelegateTree(req)

        @volatile var closed = false
        def loop(): Future[Unit] =
          if (closed) Future.Unit
          else trees.recv().transform {
            case Throw(e) =>
              state() = Activity.Failed(e)
              Future.exception(e)

            case Return(Stream.Releasable(DelegateTreeRsp(ptree), release)) =>
              ptree match {
                case None =>
                case Some(ptree) =>
                  state() = Activity.Ok(fromProtoBoundDelegateTree(ptree))
              }
              // XXX shouldn't release until this state is changed
              // later, but deferring this could currently break
              // flow-control.
              release().before(loop())
          }

        val f = loop()
        Closable.make { _ =>
          closed = true
          f.raise(Failure("closed", Failure.Interrupted))
          trees.reset(GrpcStatus.Ok())
          Future.Unit
        }
      }

      Activity(run)
    }

    // XXX should we maintain a cache of these?
    private[this] val bindAddr: Path => Var[Addr] = { id =>
      val req = mkAddrReq(id)
      Var.async[Addr](Addr.Pending) { addr =>
        val addrs = client.streamAddr(req)

        @volatile var closed = false
        def loop(): Future[Unit] =
          if (closed) Future.Unit
          else addrs.recv().transform {
            case Throw(e) =>
              addr() = Addr.Failed(e)
              Future.exception(e)
            case Return(Stream.Releasable(paddr, release)) =>
              addr() = fromProtoAddr(paddr)
              release().before(loop())
          }
        val f = loop()

        Closable.make { deadline =>
          closed = true
          f.raise(Failure("closed", Failure.Interrupted))
          addrs.reset(GrpcStatus.Ok())
          Future.Unit
        }
      }
    }

    private[this] val fromProtoBoundNameTree: BoundNameTree => NameTree[Name.Bound] =
      mkFromProtoBoundNameTree(bindAddr)

    private[this] val fromProtoBoundDelegateTree: BoundDelegateTree => DelegateTree[Name.Bound] =
      mkFromProtoBoundDelegateTree(bindAddr)

  }
}
