package io.buoyant.namer.consul

import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.consul._
import io.buoyant.consul.v1.{ServiceNode, UnexpectedResponse}
import io.buoyant.namer.Metadata

class CatalogNamer(
  idPrefix: Path,
  catalogApi: v1.CatalogApi,
  agentApi: v1.AgentApi,
  includeTag: Boolean = false,
  setHost: Boolean = false
) extends Namer {

  import CatalogNamer._

  private[this] def domainFuture(): Future[Option[String]] =
    agentApi.localAgent().map { la => la.Config.flatMap(_.Domain).map(_.stripPrefix(".").stripSuffix(".")) }

  /**
   * Accepts names in the form:
   * /<datacenter>/<svc-name>/residual/path
   * or, if `includeTag` is true, in the form:
   * /<datacenter>/<tag>/<svc-name>/residual/path
   */
  def lookup(path: Path): Activity[NameTree[Name]] =
    path match {
      case Path.Utf8(dcName, serviceName, residual@_*) if !includeTag =>
        lookup(
          dcName,
          SvcKey(serviceName, None),
          idPrefix ++ Path.Utf8(dcName, serviceName),
          Path.Utf8(residual: _*)
        )
      case Path.Utf8(dcName, tag, serviceName, residual@_*) if includeTag =>
        lookup(
          dcName,
          SvcKey(serviceName, Some(tag)),
          idPrefix ++ Path.Utf8(dcName, tag, serviceName),
          Path.Utf8(residual: _*)
        )
      case _ =>
        Activity.value(NameTree.Neg)
    }

  def lookup(
    dcName: String,
    svcKey: SvcKey,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    log.debug("consul lookup: %s %s", id.show)
    Activity(Dc.get(dcName).services).map { services =>
      log.debug("consul dc %s initial state: %s", dcName, services.keys.mkString(", "))
      services.get(svcKey) match {
        case None =>
          log.debug("consul dc %s service %s missing", dcName, svcKey)
          NameTree.Neg

        case Some(service) =>
          log.debug("consul ns %s service %s found + %s", dcName, svcKey, residual.show)
          NameTree.Leaf(Name.Bound(service.addrs, id, residual))
      }
    }
  }

  /**
   * Contains all cached responses from the Consul API
   */
  private[this] object Dc {
    private[this] val activity: ActUp[Map[String, DcCache]] =
      Var(Activity.Pending)

    /**
     * Returns existing datacenter cache with that name
     * or creates a new one
     */
    def get(name: String): DcCache = synchronized {
      activity.sample() match {
        case Activity.Ok(snap) =>
          snap.getOrElse(name, {
            val dc = new DcCache(name, Var(Activity.Pending))
            activity() = Activity.Ok(snap + (name -> dc))
            dc
          })

        case _ =>
          val dc = new DcCache(name, Var(Activity.Pending))
          activity() = Activity.Ok(Map(name -> dc))
          dc
      }
    }
  }

  /**
   * Contains all cached serviceNodes responses for a particular serviceName
   * in a particular datacenter
   */
  case class SvcCache(datacenter: String, key: SvcKey, updateableAddr: VarUp[Addr]) {

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

    def update(nodes: Seq[ServiceNode]): Future[Unit] = {
      val metaFuture =
        if (setHost)
          domainFuture().map { domainOption =>
            val domain = domainOption.getOrElse("consul")
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

  case class SvcKey(name: String, tag: Option[String]) {
    override def toString = tag match {
      case Some(t) => s"$name:$t"
      case None => name
    }
  }

  /**
   * Contains all cached serviceMap responses and the mapping of names
   * to SvcCaches for a particular datacenter.
   */
  class DcCache(name: String, activity: ActUp[Map[SvcKey, SvcCache]]) {

    def services: Var[Activity.State[Map[SvcKey, SvcCache]]] = activity

    var index = "0"
    val _ = init()

    def setIndex(idx: String) = {
      index = idx
    }

    def mkRequest(): Future[Seq[SvcKey]] =
      catalogApi
        .serviceMap(datacenter = Some(name), blockingIndex = Some(index), retry = true)
        .map { indexedSvcs =>
          indexedSvcs.index.foreach(setIndex)
          indexedSvcs.value.flatMap {
            case (svcName, tags) =>
              tags.map(tag => SvcKey(svcName, Some(tag))) :+ SvcKey(svcName, None)
          }.toSeq
        }

    def clear(): Unit = synchronized {
      activity.sample() match {
        case Activity.Ok(snap) =>
          for (svc <- snap.values) {
            svc.clear()
          }
        case _ =>
      }
      activity() = Activity.Pending
    }

    def init(): Future[Unit] =
      mkRequest().flatMap { svcKeys =>

        val services = Map(svcKeys.map { svcKey =>
          svcKey -> mkSvc(svcKey)
        }: _*)

        synchronized {
          activity() = Activity.Ok(services)
        }

        mkRequest().flatMap(update)
      }.handle {
        case e: UnexpectedResponse => activity() = Activity.Ok(Map.empty)
        case e: Throwable => activity() = Activity.Failed(e)
      }

    def update(serviceKeys: Seq[SvcKey]): Future[Unit] = {
      synchronized {
        val svcs = services.sample() match {
          case Activity.Ok(svcs) => svcs
          case _ => Map.empty[SvcKey, SvcCache]
        }

        serviceKeys.foreach { key =>
          if (!svcs.contains(key))
            add(key)
        }

        svcs.foreach {
          case (key, _) =>
            if (!serviceKeys.contains(key))
              delete(key)
        }
      }

      mkRequest().flatMap(update)
    }

    private[this] def mkSvc(svcKey: SvcKey): SvcCache = SvcCache(name, svcKey, Var(Addr.Pending))

    private[this] def add(serviceKey: SvcKey): Unit = {
      log.debug("consul added: %s", serviceKey)
      val svcs = services.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[SvcKey, SvcCache]
      }
      activity() = Activity.Ok(svcs + (serviceKey -> mkSvc(serviceKey)))
    }

    private[this] def delete(serviceKey: SvcKey): Unit = {
      log.debug("consul deleted: %s", serviceKey)
      val svcs = services.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[SvcKey, SvcCache]
      }
      svcs.get(serviceKey) match {
        case Some(svc) =>
          svc.clear()
          activity() = Activity.Ok(svcs - serviceKey)
        case _ =>
      }
    }

  }

}

object CatalogNamer {
  type VarUp[T] = Var[T] with Updatable[T]
  type ActUp[T] = VarUp[Activity.State[T]]
  private val log = Logger.get(getClass.getName)
}
