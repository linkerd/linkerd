package io.buoyant.namerd.iface

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.Buf
import com.twitter.util.{Activity, Event, Return, Var}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.linkerd.proxy.destination

class DestinationService(interpreter: NameInterpreter) extends destination.Destination {

  override def get(req: destination.GetDestination): Stream[destination.Update] = {

    if (!req.scheme.contains("k8s")) {
      val res = Stream.mk[destination.Update]
      res.close(GrpcStatus.Unimplemented("Unknown scheme: " + req.scheme.getOrElse("unknown")))
      return res
    }

    val name = Path(Seq(Buf.Utf8("svc")) ++ req.path.map(Buf.Utf8(_)): _*)

    val act = interpreter.bind(Dtab.empty, name)
    val stream = Stream.mk[destination.Update]
    val closable = act.values.respond {
      case Return(tree) =>


    }

  }

  private[this] def resolve(tree: Activity[NameTree[Name.Bound]]): Var[Addr] = {
    tree.run.flatMap {
      case Activity.Ok(tree) =>
        val vaddrs = tree.eval match {
          case Some(bounds) => bounds.map(_.addr)
          case None => Set.empty
        }
        Var.collect(vaddrs.toList).map { addrs =>
          addrs.collect { case Addr.Bound(addresses, _) => addresses }.flatten.toSet
        }
        ???

      case _ => Var()
    }
  }
}
