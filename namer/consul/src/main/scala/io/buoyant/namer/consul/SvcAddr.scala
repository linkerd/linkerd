package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul.v1
import io.buoyant.namer.Metadata

private[consul] case class SvcKey(name: String, tag: Option[String]) {
  override def toString = tag match {
    case Some(t) => s"$name:$t"
    case None => name
  }
}

private[consul] object SvcAddr {

  /**
   * Runs a long-polling loop on a service object to obtain the set of
   * Addresses.  This evaluates lazily so that only activity observed
   */
  def apply(
    consulApi: v1.ConsulApi,
    datacenter: String,
    key: SvcKey,
    domain: Option[String]
  ): Var[Addr] = Var.async[Addr](Addr.Pending) { state =>

    @volatile var index = "0"
    def setIndex(idx: Option[String]) = idx match {
      case None =>
      case Some(idx) =>
        index = idx
    }

    val indexedToAddresses: v1.Indexed[Seq[v1.ServiceNode]] => Set[Address] = {
      case v1.Indexed(nodes, idx) =>
        setIndex(idx)
        nodes.flatMap(serviceNodeToAddr).toSet
    }

    def getAddresses(): Future[Set[Address]] =
      consulApi.serviceNodes(
        key.name,
        datacenter = Some(datacenter),
        tag = key.tag,
        blockingIndex = Some(index),
        retry = true
      ).map(indexedToAddresses)

    @volatile var stopped: Boolean = false
    def loop(): Future[Unit] =
      if (stopped) Future.Unit
      else getAddresses().flatMap { addrs =>
        val addr = addrs match {
          case addrs if addrs.isEmpty => Addr.Neg
          case addrs =>
            val meta = domain match {
              case None => Addr.Metadata.empty
              case Some(domain) =>
                val authority = key.tag match {
                  case Some(tag) => s"$tag.${key.name}.service.$datacenter.$domain"
                  case None => s"${key.name}.service.$datacenter.$domain"
                }
                Addr.Metadata(Metadata.authority -> authority)
            }
            Addr.Bound(addrs.toSet, meta)
        }
        state() = addr

        loop()
      }

    val pending = loop().handle {
      case e: Throwable =>
        state() = Addr.Failed(e)
    }

    Closable.make { _ =>
      stopped = true
      pending.raise(Failure("stopped").flagged(Failure.Interrupted))
      Future.Unit
    }
  }

  /**
   * Prefer service IPs to node IPs. Invalid addresses are ignored.
   */
  private val serviceNodeToAddr: v1.ServiceNode => Traversable[Address] = { n =>
    (n.Address, n.ServiceAddress, n.ServicePort) match {
      case (_, Some(ip), Some(port)) if !ip.isEmpty => Try(Address(ip, port)).toOption
      case (Some(ip), _, Some(port)) if !ip.isEmpty => Try(Address(ip, port)).toOption
      case _ => None
    }
  }
}
