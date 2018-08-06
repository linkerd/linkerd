package io.buoyant.namerd.iface.destination

import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Address, _}
import com.twitter.io.Buf
import com.twitter.logging.Logger
import com.twitter.util.{Event, _}
import io.buoyant.grpc.runtime.VarEventStream.{Ev, Val}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream, VarEventStream}
import io.linkerd.proxy.destination.Destination
import io.linkerd.proxy.destination.Update.OneofUpdate
import io.linkerd.proxy.destination.Update.OneofUpdate.{NoEndpoints => EndpointsNone, _}
import io.linkerd.proxy.destination._
import io.linkerd.proxy.net.IPAddress.OneofIp
import io.linkerd.proxy.net.{IPAddress, TcpAddress}
import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicBoolean

class DestinationService(
  pathPfx: String,
  interpreter: NameInterpreter
) extends Destination {
  import DestinationService._

  //Helper trait to capture Failed Addresses
  sealed trait FailedAddress extends Set[Address]
  case class FAddress(e: Throwable) extends FailedAddress {
    override def contains(elem: Address): Boolean = false

    override def +(elem: Address): Set[Address] = Set.empty

    override def -(elem: Address): Set[Address] = Set.empty

    override def iterator: Iterator[Address] = Iterator.empty
  }

  //Diff implementation for Address Set
  class AddressDiffable extends Diffable[Set] {
    override def diff[T](
      left: Set[T],
      right: Set[T]
    ): Diff[Set, T] =
      (left, right) match {
        case (FAddress(e), _) => AddressDiff(Set.empty, Set.empty, Some(e))
        case (_, FAddress(e)) => AddressDiff(Set.empty, Set.empty, Some(e))
        case _ => AddressDiff(right -- left, left -- right)
      }

    override def empty[T]: Set[T] = Set.empty
  }

  case class AddressDiff[T](add: Set[T], remove: Set[T], exc: Option[Throwable] = None) extends Diff[Set, T] {
    override def patch(coll: Set[T]): Set[T] = coll ++ add -- remove

    override def map[U](f: T => U): Diff[Set, U] = AddressDiff(add.map(f), remove.map(f))
  }

  implicit val _addressDiffable = new AddressDiffable

  private[this] val log = Logger.get(getClass.getName)
  override def get(req: GetDestination): Stream[Update] = {
    log.info(s"stream initiated from request: $req")
    if (!req.scheme.contains("k8s")) {
      val res = Stream.mk[Update]
      res.close(GrpcStatus.Unimplemented("Unknown scheme: " + req.scheme.getOrElse("unknown")))
      return res
    }

    val path = req.path match {
      case None =>
        val res = Stream.mk[Update]
        res.close(GrpcStatus.Unimplemented("Empty request path"))
        return res
      case Some(p) => Buf.Utf8(p.substring(0, p.indexOf(".")))
    }

    val name = Path(Seq(Buf.Utf8(pathPfx), path): _*)
    val events = interpreter.bind(Dtab.empty, name).map(_.eval).flatMap {
      case None => Activity.value(Var.value(Addr.Bound()))
      case Some(boundSet) =>
        val set = Var.collect(boundSet.map(_.addr).toList).map(foldAddr)
        Activity.value(set)
    }.run.flatMap {
      case Activity.Failed(e) => Var(Addr.Failed(e))
      case Activity.Pending => Var(Addr.Pending)
      case Activity.Ok(t) => t
    }.changes.collect {
      case Addr.Failed(e) => FAddress(e)
      case Addr.Bound(addresses, _) => addresses
    }

    val updateEvents = eventFlatMap[Diff[Set, Address], Ev[Update]](events.diff, {
      case AddressDiff(add, remove, exc) =>
        val noEndpointsUpdate = exc match {
          case Some(_) => Seq(Val(mkNoEndpointsUpdate(false)))
          case None => Nil
        }
        val addUpdate = if (add.nonEmpty) Seq(Val(mkAddUpdate(add))) else Nil
        val removeUpdate = if (remove.nonEmpty) Seq(Val(mkRemoveUpdate(remove))) else Nil
        noEndpointsUpdate ++ addUpdate ++ removeUpdate
    })
    val totalUpdates = prependEventOnCondition[Ev[Update]](updateEvents, Val(mkNoEndpointsUpdate(true)),
      {
        case Val(Update(Some(Add(_)))) => true
        case _ => false
      })
    VarEventStream(totalUpdates)
  }

  private[this] def foldAddr(addrs: List[Addr]): Addr = {
    addrs.fold(Addr.Pending) {
      // nonPending take priority
      case (Addr.Pending, nonPending) => nonPending
      case (nonPending, Addr.Pending) => nonPending
      // Bound addresses combined
      case (a@Addr.Bound(_, _), _) => a
      case (_, a@Addr.Bound(_, _)) => a
      // otherwise, just use the first state (e.g. in a list of negs or fails)
      case (a, _) => a
    }
  }
}

object DestinationService {
  private[this] def inetAddressToInt(ip: InetAddress): Int = {
    ip.getAddress.reverse.zipWithIndex.foldRight(0) { (b, acc) =>
      val pow = b._2 * 8
      val bitShift = 1 << pow
      acc + (b._1.toInt * bitShift)
    }
  }

  //creates a sequence of events from one event based on function f
  private[destination] def eventFlatMap[T, U](ev: Event[T], f: T => Seq[U]): Event[U] = new Event[U] {
    def register(w: Witness[U]): Closable = {
      ev.respond { t =>
        for (u <- f(t)) w.notify(u)
      }
    }
  }

  //Prepends an init event to an already existing event only once.
  private[destination] def prependEventOnCondition[T](ev: Event[T], init: T, f: T => Boolean): Event[T] = new Event[T] {
    @volatile var prependOnce: AtomicBoolean = new AtomicBoolean(false)
    def register(w: Witness[T]): Closable = {
      ev.respond { t =>
        if (!prependOnce.getAndSet(true) && f(t)) {
          w.notify(init)
          w.notify(t)
        } else w.notify(t)
      }
    }
  }

  private[destination] def mkAddUpdate(addresses: Set[Address]): Update = {
    val weightedAddrSet = addresses.collect {
      case Address.Inet(socketAddress, _) =>
        WeightedAddr(
          `addr` = Some(mkTcpAddress(socketAddress)),
          `weight` = Some(1)
        )
    }.toSeq
    Update(Some(OneofUpdate.Add(WeightedAddrSet(weightedAddrSet))))
  }

  private[destination] def mkTcpAddress(addr: InetSocketAddress) = {
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

  private[destination] def mkRemoveUpdate(addresses: Set[Address]): Update = {
    val addrSet = addresses.collect {
      case Address.Inet(addr, _) =>
        mkTcpAddress(addr)
    }.toSeq
    Update(Some(Remove(AddrSet(addrSet))))
  }

  private[destination] def mkNoEndpointsUpdate(exists: Boolean) =
    Update(Some(EndpointsNone(NoEndpoints(Some(exists)))))
}
