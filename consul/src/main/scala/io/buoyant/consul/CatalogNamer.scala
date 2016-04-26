package io.buoyant.consul

import com.twitter.finagle._
import com.twitter.util._
import io.buoyant.consul.v1.ServiceNode

class CatalogNamer(
  idPrefix: Path,
  mkApi: (String) => v1.CatalogApi
) extends Namer {

  import CatalogNamer._

  /**
   * Accepts names in the form:
   * /<datacenter>/<svc-name>/residual/path
   */
  def lookup(path: Path): Activity[NameTree[Name]] =
    path.take(PrefixLen) match {
      case id@Path.Utf8(dcName, serviceName) =>
        val residual = path.drop(PrefixLen)
        log.debug("consul lookup: %s %s", id.show, path.show)
        Activity(Dc.get(dcName).services).map { services =>
          log.debug("consul dc %s initial state: %s", dcName, services.keys.mkString(", "))
          services.get(serviceName) match {
            case None =>
              log.debug("consul dc %s service %s missing", dcName, serviceName)
              NameTree.Neg

            case Some(service) =>
              log.debug("consul ns %s service %s found + %s", dcName, serviceName, residual.show)
              NameTree.Leaf(Name.Bound(service.addrs, idPrefix ++ id, residual))
          }
        }

      case _ =>
        Activity.value(NameTree.Neg)
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
   * Contains all cached serviceNodes responses for a particular serviceName in a particular datacenter
   */
  case class SvcCache(datacenter: String, name: String, updateableAddr: VarUp[Addr]) {

    def addrs: VarUp[Addr] = updateableAddr

    var index = "0"
    val api = mkApi(name)
    val _ = init()

    def setIndex(idx: String) = {
      index = idx
    }

    def mkRequest(): Future[Seq[ServiceNode]] =
      api
        .serviceNodes(name, datacenter = Some(datacenter), blockingIndex = Some(index), retry = true)
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
        case (_, Some(addr), Some(port)) if !addr.isEmpty =>
          Some(Address(addr, port))
        case (Some(addr), _, Some(port)) if !addr.isEmpty =>
          Some(Address(addr, port))
        case _ => None
      }
    }

    def update(nodes: Seq[ServiceNode]): Future[Unit] = {
      synchronized {
        try {
          val socketAddrs = nodes.flatMap(serviceNodeToAddr).toSet
          updateableAddr() = if (socketAddrs.isEmpty) Addr.Neg else Addr.Bound(socketAddrs)
        } catch {
          // in the event that we are trying to parse an invalid addr
          case e: IllegalArgumentException =>
            updateableAddr() = Addr.Failed(e)
        }
      }
      mkRequest().flatMap(update).handle(handleUnexpected)
    }

    val handleUnexpected: PartialFunction[Throwable, Unit] = {
      case e: ChannelClosedException =>
        log.error(s"""lost consul connection while querying for $datacenter/$name updates""")
      case e: Throwable =>
        updateableAddr() = Addr.Failed(e)
    }
  }

  /**
   * Contains all cached serviceMap responses and the mapping of names
   * to SvcCaches for a particular datacenter.
   */
  class DcCache(name: String, activity: ActUp[Map[String, SvcCache]]) {

    def services: Var[Activity.State[Map[String, SvcCache]]] = activity

    var index = "0"
    val api = mkApi(name)
    val _ = init()

    def setIndex(idx: String) = {
      index = idx
    }

    def mkRequest(): Future[Seq[String]] =
      api
        .serviceMap(datacenter = Some(name), blockingIndex = Some(index), retry = true)
        .map { indexedSvcs =>
          indexedSvcs.index.foreach(setIndex)
          indexedSvcs.value.keySet.toSeq
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
      mkRequest().flatMap { serviceNames =>

        val services = Map(serviceNames.map { serviceName =>
          serviceName -> mkSvc(serviceName)
        }: _*)

        synchronized {
          activity() = Activity.Ok(services)
        }

        mkRequest().flatMap(update)
      }.handle {
        case e: UnexpectedResponse => activity() = Activity.Ok(Map.empty)
        case e: Throwable => activity() = Activity.Failed(e)
      }

    def update(serviceNames: Seq[String]): Future[Unit] = {
      synchronized {
        val svcs = services.sample() match {
          case Activity.Ok(svcs) => svcs
          case _ => Map.empty[String, SvcCache]
        }

        serviceNames.foreach { serviceName =>
          if (!svcs.contains(serviceName))
            add(serviceName)
        }

        svcs.foreach {
          case (serviceName, _) =>
            if (!serviceNames.contains(serviceName))
              delete(serviceName)
        }
      }

      mkRequest().flatMap(update)
    }

    private[this] def mkSvc(serviceName: String): SvcCache = SvcCache(name, serviceName, Var(Addr.Pending))

    private[this] def add(serviceName: String): Unit = {
      log.debug("consul added: %s", serviceName)
      val svcs = services.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[String, SvcCache]
      }
      activity() = Activity.Ok(svcs + (serviceName -> mkSvc(serviceName)))
    }

    private[this] def delete(serviceName: String): Unit = {
      log.debug("consul deleted: %s", serviceName)
      val svcs = services.sample() match {
        case Activity.Ok(svcs) => svcs
        case _ => Map.empty[String, SvcCache]
      }
      svcs.get(serviceName) match {
        case Some(svc) =>
          svc.clear()
          activity() = Activity.Ok(svcs - name)
        case _ =>
      }
    }

  }

}

object CatalogNamer {
  val log = v1.log
  val PrefixLen = 2
  type VarUp[T] = Var[T] with Updatable[T]
  type ActUp[T] = VarUp[Activity.State[T]]
}

