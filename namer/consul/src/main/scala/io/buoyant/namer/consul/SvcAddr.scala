package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul.v1
import io.buoyant.namer.Metadata
import scala.util.control.NoStackTrace

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
  ): Var[Addr] = {

    def getAddresses(index: Option[String]): Future[v1.Indexed[Set[Address]]] =
      consulApi.serviceNodes(
        key.name,
        datacenter = Some(datacenter),
        tag = key.tag,
        blockingIndex = index,
        retry = true
      ).map(indexedToAddresses)

    // Start by fetching the service immediately, and then
    Var.async[Addr](Addr.Pending) { state =>
      @volatile var stopped: Boolean = false

      def loop(index0: Option[String]): Future[Unit] =
        if (stopped) Future.Unit
        else getAddresses(index0).flatMap {
          case v1.Indexed(_, None) =>
            // If consul doesn't return an index, we're in bad shape.
            Future.exception(NoIndexException)

          case v1.Indexed(addrs, index1) =>
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
            loop(index1)
        }

      val pending = loop(None).handle {
        case e: Throwable =>
          state() = Addr.Failed(e)
      }

      Closable.make { _ =>
        stopped = true
        pending.raise(ServiceRelease)
        Future.Unit
      }
    }
  }

  private[this] val indexedToAddresses: v1.Indexed[Seq[v1.ServiceNode]] => v1.Indexed[Set[Address]] = {
    case v1.Indexed(nodes, idx) =>
      val addrs = nodes.flatMap(serviceNodeToAddr).toSet
      v1.Indexed(addrs, idx)
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

  private[this] val ServiceRelease =
    Failure("service observation released").flagged(Failure.Interrupted)

  private[this] val NoIndexException =
    Failure("consul did not return an index")
}
