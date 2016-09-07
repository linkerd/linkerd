package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul._
import io.buoyant.consul.v1.{ServiceNode, UnexpectedResponse}
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
private[consul] case class SvcCache(
  catalogApi: v1.ConsulApi,
  agentApi: v1.AgentApi,
  datacenter: String,
  key: SvcKey,
  updateableAddr: VarUp[Addr],
  setHost: Boolean
) {

  def addrs: VarUp[Addr] = updateableAddr

  var index = "0"
  val _ = init()

  def setIndex(idx: String) = {
    index = idx
  }

  def mkRequest(): Future[Seq[ServiceNode]] =
    catalogApi
      .serviceNodes(key.name, datacenter = Some(datacenter), tag = key.tag, blockingIndex = Some(index), retry = true)
      .map { indexedNodes =>
        indexedNodes.index.foreach(setIndex)
        indexedNodes.value
      }

  def clear(): Unit = synchronized {
    updateableAddr() = Addr.Pending
  }

  def init(): Future[Unit] = mkRequest().flatMap(update).handle(handleUnexpected)

  def serviceNodeToAddr(node: ServiceNode): Option[Address] = {
    (node.Address, node.ServiceAddress, node.ServicePort) match {
      case (_, Some(serviceIp), Some(port)) if !serviceIp.isEmpty =>
        Some(Address(serviceIp, port))
      case (Some(nodeIp), _, Some(port)) if !nodeIp.isEmpty =>
        Some(Address(nodeIp, port))
      case _ => None
    }
  }

  private[this] def domainFuture(): Future[String] =
    agentApi.localAgent().map { la =>
      la.Config.flatMap(_.Domain).getOrElse("consul")
        .stripPrefix(".").stripSuffix(".")
    }

  def update(nodes: Seq[ServiceNode]): Future[Unit] = {
    val metaFuture =
      if (setHost)
        domainFuture().map { domain =>
          val authority = key.tag match {
            case Some(tag) => s"$tag.${key.name}.service.$datacenter.$domain"
            case None => s"${key.name}.service.$datacenter.$domain"
          }
          Addr.Metadata(Metadata.authority -> authority)
        }
      else
        Future.value(Addr.Metadata.empty)

    metaFuture.flatMap { meta =>
      synchronized {
        try {
          val socketAddrs = nodes.flatMap(serviceNodeToAddr).toSet
          updateableAddr() = if (socketAddrs.isEmpty) Addr.Neg else Addr.Bound(socketAddrs, meta)
        } catch {
          // in the event that we are trying to parse an invalid addr
          case e: IllegalArgumentException =>
            updateableAddr() = Addr.Failed(e)
        }
      }
      mkRequest().flatMap(update).handle(handleUnexpected)
    }
  }

  val handleUnexpected: PartialFunction[Throwable, Unit] = {
    case e: ChannelClosedException =>
      log.error(s"""lost consul connection while querying for $datacenter/$key updates""")
    case e: Throwable =>
      updateableAddr() = Addr.Failed(e)
  }
}
