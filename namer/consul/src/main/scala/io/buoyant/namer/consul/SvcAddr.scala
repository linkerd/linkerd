package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.finagle.stats.StatsReceiver
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

  case class Stats(stats: StatsReceiver) {
    val opens = stats.counter("opens")
    val closes = stats.counter("closes")
    val errors = stats.counter("errors")
    val updates = stats.counter("updates")
  }

  /**
   * Runs a long-polling loop on a service object to obtain the set of
   * Addresses.  This evaluates lazily so that only activity observed
   */
  def apply(
    consulApi: v1.ConsulApi,
    datacenter: String,
    key: SvcKey,
    domain: Option[String],
    consistency: Option[v1.ConsistencyMode] = None,
    preferServiceAddress: Option[Boolean] = None,
    stats: Stats
  ): Var[Addr] = {
    val meta = mkMeta(key, datacenter, domain)

    def getAddresses(index: Option[String]): Future[v1.Indexed[Set[Address]]] =
      consulApi.serviceNodes(
        key.name,
        datacenter = Some(datacenter),
        tag = key.tag,
        blockingIndex = index,
        consistency = consistency,
        retry = true
      ).map(indexedToAddresses(preferServiceAddress))

    // Start by fetching the service immediately, and then long-poll
    // for service updates.
    Var.async[Addr](Addr.Pending) { state =>
      stats.opens.incr()
      // last good state - if an error occurs, fall back to this if
      // a good state was seen.
      @volatile var lastGood: Option[Addr] = None
      @volatile var stopped: Boolean = false
      def loop(index0: Option[String]): Future[Unit] = {
        if (stopped) Future.Unit
        else getAddresses(index0).transform {
          case Throw(Failure(Some(err: ConnectionFailedException))) =>
            log.warning(
              "consul datacenter '%s' service '%s' retrying on connection" +
                " failure: %s",
              datacenter, key.name, err
            )
            // Drop the index, in case it's been reset by a consul restart
            loop(None)
          case Throw(e) =>
            stats.errors.incr()
            lastGood match {
              case Some(addr) =>
                // if we've already seen a good state, fall back to that and
                // try again.
                state() = addr
                log.warning(
                  "consul datacenter '%s' service '%s' observation error %s," +
                    " falling back to last good state",
                  datacenter, key.name, e
                )
                loop(index0)
              case None =>
                // if no previous good state was seen, treat the exception
                // as effectively fatal to the service observation.
                state() = Addr.Failed(e)
                log.error(
                  "consul datacenter '%s' service '%s' observation error %s," +
                    " no previous good state to fall back to!",
                  datacenter, key.name, e
                )
                Future.exception(e)
            }

          case Return(v1.Indexed(_, None)) =>
            // If consul doesn't return an index, we're in bad shape.
            // TODO: do we want to revert to the last good state here, as well?
            state() = Addr.Failed(NoIndexException)
            stats.errors.incr()
            log.error(
              "consul datacenter '%s' service '%s' didn't return an index!",
              datacenter, key.name
            )
            Future.exception(NoIndexException)

          case Return(v1.Indexed(addrs, index1)) =>
            stats.updates.incr()
            val addr = addrs match {
              case addrs if addrs.isEmpty => Addr.Neg
              case addrs => Addr.Bound(addrs, meta)
            }
            lastGood = Some(addr)
            state() = addr

            loop(index1)
        }
      }

      val pending = loop(None)
      Closable.make { _ =>
        stopped = true
        stats.closes.incr()
        pending.raise(ServiceRelease)
        Future.Unit
      }
    }
  }

  private[this] def mkMeta(key: SvcKey, dc: String, domain: Option[String]) =
    domain match {
      case None => Addr.Metadata.empty
      case Some(domain) =>
        val authority = key.tag match {
          case Some(tag) => s"${tag}.${key.name}.service.${dc}.${domain}"
          case None => s"${key.name}.service.${dc}.${domain}"
        }
        Addr.Metadata(Metadata.authority -> authority)
    }

  private[this] def indexedToAddresses(preferServiceAddress: Option[Boolean]): v1.Indexed[Seq[v1.ServiceNode]] => v1.Indexed[Set[Address]] = {
    case v1.Indexed(nodes, idx) =>
      val addrs = preferServiceAddress match {
        case Some(false) => nodes.flatMap(serviceNodeToNodeAddr).toSet
        case _ => nodes.flatMap(serviceNodeToAddr).toSet
      }
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

  /**
   * Always use node IPs. Invalid addresses are ignored.
   */
  private val serviceNodeToNodeAddr: v1.ServiceNode => Traversable[Address] = { n =>
    (n.Address, n.ServicePort) match {
      case (Some(ip), Some(port)) if !ip.isEmpty => Try(Address(ip, port)).toOption
      case _ => None
    }
  }

  private[this] val ServiceRelease =
    Failure("service observation released", Failure.Interrupted)

  private[this] val NoIndexException =
    Failure(new IllegalArgumentException("consul did not return an index") with NoStackTrace)
}
