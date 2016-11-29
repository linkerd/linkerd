package io.buoyant.namerd
package iface.grpc

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Closable, Future, Return, Throw, Try, Var}
import io.buoyant.grpc.runtime.{Stream, EventStream}
import io.buoyant.namer.ConfiguredDtabNamer
import io.buoyant.proto.BoundNameTree
import io.buoyant.proto.namerd.{Interpreter => ProtoInterpreter, BoundTreeRsp}

object InterpreterClient {
  import InterpreterProto._

  def apply(client: ProtoInterpreter, namespace: String): NameInterpreter =
    Impl(client, namespace)

  case class Impl(client: ProtoInterpreter, namespace: String)
    extends NameInterpreter {

    private[this] val fromProtoBoundNameTree: BoundNameTree => NameTree[Name.Bound] =
      mkFromProtoBoundNameTree { id =>
        val req = mkAddrReq(id)
        Var.async[Addr](Addr.Pending) { addr =>
          val stream = client.streamAddr(req)
          @volatile var closed = false
          def loop(): Future[Unit] =
            if (closed) Future.Unit
            else stream.recv().transform {
              case Throw(e) =>
                addr() = Addr.Failed(e)
                Future.exception(e)

              case Return(Stream.Releasable(paddr, release)) =>
                addr() = fromProtoAddr(paddr)
                release().before(loop())
            }
          val f = loop()
          Closable.make { deadline =>
            // TODO cancel strema
            closed = true
            f.raise(Failure("closed", Failure.Interrupted))
            Future.Unit
          }
        }
      }

    override def bind(dtab: Dtab, path: Path): Activity[NameTree[Name.Bound]] = {
      val run = Var.async[Activity.State[NameTree[Name.Bound]]](Activity.Pending) { state =>
        val req = mkBindReq(namespace, path, dtab)
        val stream = client.streamBoundTree(req)
        @volatile var closed = false
        def loop(): Future[Unit] =
          if (closed) Future.Unit
          else stream.recv().transform {
            case Throw(e) =>
              state() = Activity.Failed(e)
              Future.exception(e)
            case Return(Stream.Releasable(rsp, release)) =>
              rsp.result match {
                case Some(BoundTreeRsp.OneofResult.Tree(ptree)) =>
                  state() = Activity.Ok(fromProtoBoundNameTree(ptree))
                case _ =>
              }
              release().before(loop())
          }
        val f = loop()
        Closable.make { deadline =>
          // TODO Reset stream
          closed = true
          f.raise(Failure("closed", Failure.Interrupted))
          Future.Unit
        }
      }
      Activity(run)
    }
  }
}
