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

/**
 * Contains all cached serviceNodes responses for a particular serviceName
 * in a particular datacenter
 */
private[consul] class SvcCache(
  consulApi: v1.ConsulApi,
  datacenter: String,
  key: SvcKey,
  domain: Option[String]
) {

  private[this] val updatableAddr = Var[Addr](Addr.Pending)
  def addrs: VarUp[Addr] = updatableAddr

  @volatile private[this] var index = "0"
  private[this] def setIndex(idx: Option[String]) = idx match {
    case None =>
    case Some(idx) =>
      index = idx
  }

  /**
   * Prefer service IPs to node IPs. Invalid addresses are ignored.
   */
  private[this] val serviceNodeToAddr: v1.ServiceNode => Traversable[Address] = { n =>
    (n.Address, n.ServiceAddress, n.ServicePort) match {
      case (_, Some(ip), Some(port)) if !ip.isEmpty => Try(Address(ip, port)).toOption
      case (Some(ip), _, Some(port)) if !ip.isEmpty => Try(Address(ip, port)).toOption
      case _ => None
    }
  }

  private[this] val indexedToAddresses: v1.Indexed[Seq[v1.ServiceNode]] => Set[Address] = {
    case v1.Indexed(nodes, idx) =>
      setIndex(idx)
      nodes.flatMap(serviceNodeToAddr).toSet
  }

  private[this] def getAddresses(): Future[Set[Address]] =
    consulApi.serviceNodes(
      key.name,
      datacenter = Some(datacenter),
      tag = key.tag,
      blockingIndex = Some(index),
      retry = true
    ).map(indexedToAddresses)

  @volatile private[this] var stopped: Boolean = false

  private[this] def watch(): Future[Unit] =
    if (stopped) Future.Unit
    else getAddresses().flatMap(updateAndWatch)

  private[this] val updateAndWatch: Set[Address] => Future[Unit] = { addrs =>
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
    updatableAddr() = addr
    watch()
  }

  private[this] val pending: Future[Unit] =
    watch().onFailure {
      case e: Throwable =>
        updatableAddr() = Addr.Failed(e)
    }

  def clear(): Unit = {
    stopped = true
    val e = Failure("stopped").flagged(Failure.Interrupted)
    pending.raise(e)
    updatableAddr() = Addr.Failed(e)
  }
}
