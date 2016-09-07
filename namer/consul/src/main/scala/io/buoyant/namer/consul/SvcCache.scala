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

  private[this] val indexed: v1.Indexed[Seq[v1.ServiceNode]] => Seq[v1.ServiceNode] = {
    case v1.Indexed(nodes, idx) =>
      setIndex(idx)
      nodes
  }

  private[this] def mkRequest(): Future[Seq[v1.ServiceNode]] =
    consulApi.serviceNodes(
      key.name,
      datacenter = Some(datacenter),
      tag = key.tag,
      blockingIndex = Some(index),
      retry = true
    ).map(indexed)

  def clear(): Unit = {
    updatableAddr() = Addr.Pending
  }

  private[this] def run(): Future[Unit] =
    mkRequest().flatMap(update).handle(handleUnexpected)

  def update(nodes: Seq[v1.ServiceNode]): Future[Unit] = {
    val addr = nodes.flatMap(serviceNodeToAddr) match {
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
    run()
  }

  private[this] val running = run()

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

  private[this] val handleUnexpected: PartialFunction[Throwable, Unit] = {
    case e: ChannelClosedException =>
      // XXX why doesn't this clear out the addr?
      log.error(s"""lost consul connection while querying for $datacenter/$key updates""")

    case e: Throwable =>
      updatableAddr() = Addr.Failed(e)
  }
}
