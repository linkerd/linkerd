package io.buoyant.namerd
package iface.mesh

import com.twitter.finagle.{Addr, Address, NameTree, Namer, Path}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.grpc.runtime.{Stream, VarEventStream}
import io.linkerd.mesh
import io.linkerd.mesh.Converters._
import io.buoyant.namer.{Metadata, Paths}
import java.net.Inet6Address

object ResolverService {

  def apply(namers: Map[Path, Namer], stats: StatsReceiver): mesh.Resolver.Server =
    new mesh.Resolver.Server(new ServerImpl(namers, stats))

  class ServerImpl(namers: Map[Path, Namer], stats: StatsReceiver) extends mesh.Resolver {

    override def getReplicas(req: mesh.ReplicasReq): Future[mesh.Replicas] = req match {
      case mesh.ReplicasReq(None | Some(mesh.Path(Nil))) => Future.value(ReplicasErrorNoId)
      case mesh.ReplicasReq(Some(pid)) => bind(pid).changes.toFuture.map(toReplicas)
    }

    override def streamReplicas(req: mesh.ReplicasReq): Stream[mesh.Replicas] = req match {
      case mesh.ReplicasReq(None | Some(mesh.Path(Nil))) => Stream.value(ReplicasErrorNoId)
      case mesh.ReplicasReq(Some(pid)) => VarEventStream(bind(pid).map(toReplicasEv))
    }

    private[this] def bind(pid: mesh.Path): Var[Addr] = {
      val id = Paths.stripTransformerPrefix(fromPath(pid))
      val (pfx, namer) = namers
        .find { case (pfx, _) => id.startsWith(pfx) }
        .getOrElse(DefaultNamer)

      namer.bind(NameTree.Leaf(id.drop(pfx.size))).run.flatMap {
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
    }
  }
  private[this] val DefaultNamer: (Path, Namer) =
    Path.empty -> Namer.global

  private[this] def ReplicasError(msg: String): mesh.Replicas =
    mesh.Replicas(Some(mesh.Replicas.OneofResult.Failed(mesh.Replicas.Failed(Some(msg)))))

  private[this] val ReplicasErrorNoId = ReplicasError("No ID provided")
  private[this] val ReplicasPending = mesh.Replicas.OneofResult.Pending(mesh.Replicas.Pending())
  private[this] val ReplicasNeg = mesh.Replicas.OneofResult.Neg(mesh.Replicas.Neg())

  private[this] val toReplicasResult: Addr => mesh.Replicas.OneofResult = {
    case Addr.Pending => ReplicasPending
    case Addr.Neg => ReplicasNeg
    case Addr.Failed(exc) =>
      mesh.Replicas.OneofResult.Failed(mesh.Replicas.Failed(Option(exc.getMessage)))

    case Addr.Bound(addrs, meta) =>
      val paddrs = addrs.collect(_collectToEndpoint).toSeq
      mesh.Replicas.OneofResult.Bound(mesh.Replicas.Bound(paddrs, meta.mapValues(_.toString)))
  }

  private[this] val toReplicas: Addr => mesh.Replicas =
    a => mesh.Replicas(Some(toReplicasResult(a)))

  private[this] val toReplicasEv: Addr => VarEventStream.Ev[mesh.Replicas] =
    a => VarEventStream.Val(toReplicas(a))

  private[this] val _collectToEndpoint: PartialFunction[Address, mesh.Endpoint] = {
    case Address.Inet(isa, meta) =>
      val port = isa.getPort
      val pmeta = mesh.Endpoint.Meta(
        nodeName = meta.get(Metadata.nodeName).map(_.toString)
      )
      isa.getAddress match {
        case ip: Inet6Address =>
          mesh.Endpoint(
            Some(mesh.Endpoint.AddressFamily.INET6),
            Some(Buf.ByteArray.Owned(ip.getAddress)),
            Some(port),
            Some(pmeta),
            meta.mapValues(_.toString)
          )
        case ip =>
          mesh.Endpoint(
            Some(mesh.Endpoint.AddressFamily.INET4),
            Some(Buf.ByteArray.Owned(ip.getAddress)),
            Some(port),
            Some(pmeta),
            meta.mapValues(_.toString)
          )
      }
  }

}
