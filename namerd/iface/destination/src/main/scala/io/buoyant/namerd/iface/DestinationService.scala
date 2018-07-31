package io.buoyant.namerd.iface

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.linkerd.proxy.destination
import io.linkerd.proxy.destination.Update.{OneofUpdate, _}
import io.linkerd.proxy.destination.Update.OneofUpdate.{NoEndpoints => EndpointsNone, _}
import io.linkerd.proxy.destination.{NoEndpoints, Update, WeightedAddr, WeightedAddrSet}
import io.linkerd.proxy.net.IPAddress.OneofIp
import io.linkerd.proxy.net.{IPAddress, IPv6, TcpAddress}
import java.net.{Inet4Address, Inet6Address, InetAddress}

class DestinationService(interpreter: NameInterpreter) extends destination.Destination {
  import DestinationService._
  override def get(req: destination.GetDestination): Stream[destination.Update] = {

    if (!req.scheme.contains("k8s")) {
      val res = Stream.mk[destination.Update]
      res.close(GrpcStatus.Unimplemented("Unknown scheme: " + req.scheme.getOrElse("unknown")))
      return res
    }

    val name = Path(Seq(Buf.Utf8("svc")) ++ req.path.map(Buf.Utf8(_)): _*)

    val act = interpreter.bind(Dtab.empty, name).run.flatMap {
      case Activity.Ok(t) => t match {
        case NameTree.Leaf(value) => AddressestoUpdateVar(value.addr).map(was => Update(Some(Add(was))))
        case _ => Var.value(Update(Some(Add(WeightedAddrSet(Nil, Map("set" -> "Set"))))))
      }
      case Activity.Pending => Var.value(Update(Some(EndpointsNone(NoEndpoints(Some(false))))))
      case Activity.Failed(_) => Var.value(Update(Some(EndpointsNone(NoEndpoints(Some(false))))))
    }

    val stream = Stream.mk[destination.Update]
    val closable = act.changes.respond { update => val _ = stream.send(update) }
    stream
  }

  private def AddressestoUpdateVar(addr: Var[Addr]) = {
    addr.map {
      case Addr.Bound(setAddr, _) => AddressSetToWeightedAddress(setAddr)
      case _ => WeightedAddrSet(Nil, Map("set" -> "Set"))
    }
  }

  private[this] def AddressSetToWeightedAddress(addresses: Set[Address]) = {
    WeightedAddrSet(addresses.toSeq.map(_toWeightedAddr))
  }

  private[this] def _toWeightedAddr(addr: Address) = {
    val tcpAddress = addr match {
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
    WeightedAddr(Some(tcpAddress))
  }
}

object DestinationService {
  def inetAddressToInt(ip: InetAddress): Int = {
    ip.getAddress.reverse.zipWithIndex.foldRight(0) { (b, acc) =>
      val pow = b._2 * 8
      val bitShift = 1 << pow
      println(s" current Val: $acc pow: $pow shift: $bitShift Tuple: $b")
      acc + (b._1.toInt * bitShift)
    }
  }

  def mkUpdateAdd(ipAddress: String, port: Int) = {
    val address = Some(IPAddress(Some(OneofIp.Ipv4(inetAddressToInt(InetAddress.getByName(ipAddress))))))
    val portNum = Some(port)
    Some(OneofUpdate.Add(
      WeightedAddrSet(
        Seq(WeightedAddr(Some(TcpAddress(address, portNum))))
      )
    ))
  }
}
