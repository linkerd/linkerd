package io.buoyant.namerd.iface

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.linkerd.proxy.destination
import io.linkerd.proxy.destination.Update.{OneofUpdate, _}
import io.linkerd.proxy.destination.Update.OneofUpdate.{NoEndpoints => EndpointsNone, _}
import io.linkerd.proxy.destination._
import io.linkerd.proxy.net.IPAddress.OneofIp
import io.linkerd.proxy.net.{IPAddress, TcpAddress}
import java.net.{Inet4Address, Inet6Address, InetAddress}

class DestinationService(
  interpreter: NameInterpreter
) extends destination.Destination {
  import DestinationService._

  @volatile private[this] var PathCache = Map.empty[String, Set[Address]]
  private[this] val PatchCacheMu = new {}

  override def get(req: destination.GetDestination): Stream[destination.Update] = {
    if (!req.scheme.contains("k8s")) {
      val res = Stream.mk[destination.Update]
      res.close(GrpcStatus.Unimplemented("Unknown scheme: " + req.scheme.getOrElse("unknown")))
      return res
    }

    val name = Path(Seq(Buf.Utf8("svc")) ++ req.path.map(Buf.Utf8(_)): _*)

    val stream = Stream.mk[Update]
    val act = interpreter.bind(Dtab.empty, name).run.changes.respond {
      case Activity.Pending =>
        val _ = Var.value(Update(Some(EndpointsNone(NoEndpoints(Some(false)))))).changes.respond { update =>
          stream.send(update); ()
        }
      case Activity.Failed(_) =>
        val _ = Var.value(Update(Some(EndpointsNone(NoEndpoints(Some(false)))))).changes.respond { update =>
          stream.send(update); ()
        }
      case Activity.Ok(t) => t.eval match {
        case None =>
          val _ = Var.value(Update(Some(EndpointsNone(NoEndpoints(Some(false)))))).changes.respond { update =>
            stream.send(update); ()
          }
        case Some(value) =>
          val _ = updates(name, value).respond {
            case AddressDiff(add, remove) =>
              if (add.nonEmpty) stream.send(Update(Some(Add(AddressSetToWeightedAddress(add))))); ()
              if (remove.nonEmpty) stream.send(Update(Some(Remove(AddressSetToAddrSet(remove))))); ()

              if (add.isEmpty && remove.isEmpty) stream.send(Update(Some(EndpointsNone(NoEndpoints(Some(true)))))); ()
          }
        case _ =>
          val _ = Var.value(Update(Some(Add(WeightedAddrSet(Nil, Map("set" -> "Set")))))).changes.respond { update =>
            stream.send(update); ()
          }
      }
    }
    stream
  }

  private def foldAddr(addrs: List[Addr]) = {
    addrs.fold(Addr.Pending) {
      case (Addr.Pending, Addr.Pending) => Addr.Pending
      case (Addr.Pending, Addr.Neg) => Addr.Neg
      case (Addr.Pending, Addr.Bound(addresses, _)) => Addr.Bound(addresses)
      case (Addr.Bound(prevAddr, _), Addr.Bound(curAddr, _)) => Addr.Bound(prevAddr ++ curAddr)
      case _ => Addr.Bound(Set.empty[Address])
    }
  }

  private def updates(
    name: Path,
    value: Set[Name.Bound]
  ) = {

    implicit val _addressDiffable = new AddressDiffable
    val vaddrs = value.map(_.addr)
    val foldedAddr = Var.collect(vaddrs.toList).map(foldAddr)

    val difference = foldedAddr.map {
      case Addr.Pending | Addr.Neg => Set.empty[Address]
      case Addr.Bound(addresses, _) => addresses
      case _ => Set.empty[Address]
    }
    difference.diff
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

  private[this] def DiffAddressSet(oldSet: Set[Address], newSet: Set[Address]) = {
    (oldSet -- newSet, newSet -- oldSet)
  }

  private[this] def AddressSetToWeightedAddress(addresses: Set[Address]) = {
    WeightedAddrSet(addresses.toSeq.map(_toWeightedAddr))
  }
  private[this] def AddressSetToAddrSet(addresses: Set[Address]) = {
    AddrSet(addresses.toSeq.map(_toTcpAddress))
  }

  private[this] def _toWeightedAddr(addr: Address) = {
    val tcpAddress = _toTcpAddress(addr)
    WeightedAddr(Some(tcpAddress))
  }

  private[this] def _toTcpAddress(addr: Address) = {
    addr match {
      case Address.Inet(inet, meta) => inet.getAddress match {
        case _: Inet4Address =>
          TcpAddress(
            Some(IPAddress(Some(OneofIp.Ipv4(inetAddressToInt(inet.getAddress))))),
            Some(inet.getPort)
          )

        case _: Inet6Address => TcpAddress(Some(IPAddress()), Some(9999))
      }
      case _ => TcpAddress() //TODO: Remove to advance in test cases
    }
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

  def mkUpdateAdd(addresses: Seq[(String, Int)]) = {
    val weightedAddrSet = addresses.map { address =>
      WeightedAddr(Some(TcpAddress(
        Some(IPAddress(Some(OneofIp.Ipv4(inetAddressToInt(InetAddress.getByName(address._1)))))),
        Some(address._2)
      )))

    }
    Some(OneofUpdate.Add(
      WeightedAddrSet(weightedAddrSet)
    ))
  }

  def mkUpdateRemove(addresses: Seq[(String, Int)]) = {
    val addrSet = addresses.map { address =>
      TcpAddress(
        Some(IPAddress(Some(OneofIp.Ipv4(inetAddressToInt(InetAddress.getByName(address._1)))))),
        Some(address._2)
      )
    }
    Some(OneofUpdate.Remove(
      AddrSet(addrSet)
    ))
  }
}
