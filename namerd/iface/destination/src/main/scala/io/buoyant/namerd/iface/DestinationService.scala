package io.buoyant.namerd.iface

import com.twitter.finagle.{Address, _}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.linkerd.proxy.destination
import io.linkerd.proxy.destination.Update.{OneofUpdate, _}
import io.linkerd.proxy.destination.Update.OneofUpdate.{NoEndpoints => EndpointsNone, _}
import io.linkerd.proxy.destination._
import io.linkerd.proxy.net.IPAddress.OneofIp
import io.linkerd.proxy.net.{IPAddress, TcpAddress}
import java.net.{InetAddress, InetSocketAddress}

class DestinationService(
  interpreter: NameInterpreter
) extends destination.Destination {
  import DestinationService._

  implicit val _addressDiffable = new AddressDiffable

  private[this] val log = Logger.get(getClass.getName)
  override def get(req: destination.GetDestination): Stream[destination.Update] = {
    log.info(s"stream initiated from request: $req")
    if (!req.scheme.contains("k8s")) {
      val res = Stream.mk[destination.Update]
      res.close(GrpcStatus.Unimplemented("Unknown scheme: " + req.scheme.getOrElse("unknown")))
      return res
    }

    val name = Path(Seq(Buf.Utf8("svc")) ++ req.path.map(Buf.Utf8(_)): _*)

    val stream = Stream.mk[Update]
    interpreter.bind(Dtab.empty, name).run.changes.respond {
      case Activity.Pending =>
        stream.send(mkNoEndpointsUpdate(false)); ()
      case Activity.Failed(e) =>
        log.info(s"address lookup failed: $e")
        stream.send(mkNoEndpointsUpdate(false)); ()
      case Activity.Ok(t) => t.eval match {
        case None =>
          stream.send(mkNoEndpointsUpdate(true)); ()
        case Some(value) =>
          val _ = updates(name, value).diff.respond {
            case AddressDiff(add, remove) =>
              if (add.nonEmpty) stream.send(mkAddUpdate(add)); ()
              if (remove.nonEmpty) stream.send(mkRemoveUpdate(remove)); ()
              if (add.isEmpty && remove.isEmpty) stream.send(mkNoEndpointsUpdate(true)); ()
          }
      }
    }
    stream
  }

  private[this] def foldAddr(addrs: List[Addr]) = {
    addrs.fold(Addr.Pending) {
      case (Addr.Pending, Addr.Pending) => Addr.Pending
      case (Addr.Pending, Addr.Neg) => Addr.Neg
      case (Addr.Pending, Addr.Bound(addresses, _)) => Addr.Bound(addresses)
      case (Addr.Bound(prevAddr, _), Addr.Bound(curAddr, _)) => Addr.Bound(prevAddr ++ curAddr)
      case _ => Addr.Bound(Set.empty[Address])
    }
  }

  private[this] def updates(
    name: Path,
    value: Set[Name.Bound]
  ) :Var[Set[Address]] = {
    val vaddrs = value.map(_.addr)
    val foldedAddr = Var.collect(vaddrs.toList).map(foldAddr)

    val difference = foldedAddr.map {
      case Addr.Pending | Addr.Neg => Set.empty[Address]
      case Addr.Bound(addresses, _) => addresses
      case _ => Set.empty[Address]
    }
    difference
  }

  class AddressDiffable extends Diffable[Set] {
    override def diff[T](
      left: Set[T],
      right: Set[T]
    ): Diff[Set, T] = AddressDiff(right -- left, left -- right)

    override def empty[T]: Set[T] = Set.empty
  }

  case class AddressDiff[T](add: Set[T], remove: Set[T]) extends Diff[Set, T] {
    override def patch(coll: Set[T]): Set[T] = coll ++ add -- remove

    override def map[U](f: T => U): Diff[Set, U] = AddressDiff(add.map(f), remove.map(f))
  }
}

object DestinationService {
  def inetAddressToInt(ip: InetAddress): Int = {
    ip.getAddress.reverse.zipWithIndex.foldRight(0) { (b, acc) =>
      val pow = b._2 * 8
      val bitShift = 1 << pow
      acc + (b._1.toInt * bitShift)
    }
  }

  def mkAddUpdate(addresses: Set[Address]): Update = {
    val weightedAddrSet = addresses.collect {
      case Address.Inet(socketAddress, _) =>
        WeightedAddr(
          `addr` = Some(mkTcpAddress(socketAddress)),
          `weight` = Some(1)
        )
    }.toSeq
    Update(Some(OneofUpdate.Add(WeightedAddrSet(weightedAddrSet))))
  }

  private def mkTcpAddress(addr: InetSocketAddress) = {
    TcpAddress(
      Some(
        IPAddress(
          Some(
            OneofIp
              .Ipv4(inetAddressToInt(InetAddress.getByName(addr.getHostString)))
          )
        )
      ),
      Some(addr.getPort)
    )
  }

  def mkRemoveUpdate(addresses: Set[Address]): Update = {
    val addrSet = addresses.collect {
      case Address.Inet(addr, _) =>
        mkTcpAddress(addr)
    }.toSeq
    Update(Some(Remove(AddrSet(addrSet))))
  }

  def mkNoEndpointsUpdate(exists: Boolean) = Update(Some(EndpointsNone(NoEndpoints(Some(exists)))))
}
