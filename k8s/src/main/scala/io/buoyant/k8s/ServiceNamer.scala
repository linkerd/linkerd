package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.Ns.ObjectCache
import io.buoyant.k8s.v1._
import java.net.InetSocketAddress
import scala.collection.mutable

/**
 * Accepts names in the form:
 *   /<namespace>/<port-name>/<svc-name>/residual/path
 *
 * and attempts to bind an Addr by resolving to the external load balancer
 * for the given service and port.
 */
class ServiceNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends Namer {

  private[this] val PrefixLen = 3
  private[this] val variablePrefixLength = PrefixLen + labelName.size

  def lookup(path: Path): Activity[NameTree[Name]] = (path.take(variablePrefixLength), labelName) match {
    case (id@Path.Utf8(nsName, portName, serviceName), None) =>
      val nameTree = serviceNs.get(nsName.toLowerCase, None).get(serviceName.toLowerCase, portName.toLowerCase).map(toNameTree(path, _))
      Activity(nameTree.map(Activity.Ok(_)))

    case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
      val nameTree = serviceNs
        .get(nsName.toLowerCase, Some(s"$label=$labelValue"))
        .get(serviceName.toLowerCase, portName.toLowerCase)
        .map(toNameTree(path, _))

      Activity(nameTree.map(Activity.Ok(_)))

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] def toNameTree(path: Path, svcAddress: Option[Var[Address]]): NameTree[Name.Bound] = svcAddress match {
    case Some(address) =>
      val residual = path.drop(variablePrefixLength)
      val id = path.take(variablePrefixLength)
      val bound = address.map(Addr.Bound(_))
      NameTree.Leaf(Name.Bound(bound, idPrefix ++ id, residual))
    case None =>
      NameTree.Neg
  }

  private[this] def getPort(service: Service, portName: String): Option[Int] =
    for {
      spec <- service.spec
      port <- spec.ports.find(_.name == portName)
    } yield port.port

  private[this] val serviceNs = new Ns[Service, ServiceWatch, ServiceList, ServiceCache](backoff, timer) {
    override protected def mkResource(name: String) = mkApi(name).services
    override protected def mkCache(name: String) = new ServiceCache(name)
  }
}

class ServiceCache(namespace: String)
  extends ObjectCache[Service, ServiceWatch, ServiceList] {

  /**
   * We can stabilize this by changing the type to Var[Option[Var[T]]].
   * If this Option changes from None to Some or vice versa, the outer Var will
   * update.  If the value contained in the Some changes, only the inner Var
   * will update.
   */
  private[this] def stabilize[T](unstable: Var[Option[T]]): Var[Option[Var[T]]] = {
    val init = unstable.sample().map(Var(_))
    Var.async[Option[VarUp[T]]](init) { update =>
      // the current inner Var, null if the outer Var is None
      @volatile var current: VarUp[T] = null

      unstable.changes.respond {
        case Some(t) if current == null =>
          // T created
          current = Var(t)
          update() = Some(current)
        case Some(t) =>
          // T modified
          current() = t
        case None =>
          // T deleted
          current = null
          update() = None
      }
    }
  }

  def get(serviceName: String, portName: String): Var[Option[Var[Address]]] = synchronized {
    // we call this unstable because every change to the Address will cause
    // the entire Var[Option[Address]] to update.
    val unstable: Var[Option[Address]] = cache.get(serviceName) match {
      case Some(ports) =>
        ports.map(_.ports.get(portName))
      case None =>
        val ports = Var(ServiceCache.CacheEntry(Map.empty, Map.empty))
        cache += serviceName -> ports
        ports.map(_.ports.get(portName))
    }

    stabilize(unstable)
  }

  def getPortMapping(serviceName: String, port: Int): Var[Option[Var[Int]]] = synchronized {
    // we call this unstable because every change to the target port will cause
    // the entire Var[Option[Int]] to update.
    val unstable: Var[Option[Int]] = cache.get(serviceName) match {
      case Some(ports) =>
        ports.map(_.portMap.get(port))
      case None =>
        val ports = Var(ServiceCache.CacheEntry(Map.empty, Map.empty))
        cache += serviceName -> ports
        ports.map(_.portMap.get(port))
    }

    stabilize(unstable)
  }

  private[this]type VarUp[T] = Var[T] with Updatable[T]

  private[this] var cache = Map.empty[String, VarUp[ServiceCache.CacheEntry]]

  def initialize(list: ServiceList): Unit = synchronized {
    val services = for {
      service <- list.items
      meta <- service.metadata
      name <- meta.name
    } yield name -> Var(ServiceCache.extractPorts(service))
    cache = services.toMap
  }

  def update(watch: ServiceWatch): Unit = synchronized {
    watch match {
      case ServiceAdded(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          log.info("k8s ns %s added service: %s", namespace, name)
          cache.get(name) match {
            case Some(ports) =>
              ports() = ServiceCache.extractPorts(service)
            case None =>
              cache += (name -> Var(ServiceCache.extractPorts(service)))
          }
        }
      case ServiceModified(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          cache.get(name) match {
            case Some(ports) =>
              log.info("k8s ns %s modified service: %s", namespace, name)
              ports() = ServiceCache.extractPorts(service)
            case None =>
              log.warning("k8s ns %s received modified watch for unknown service %s", namespace, name)
          }
        }
      case ServiceDeleted(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          log.debug("k8s ns %s deleted service : %s", namespace, name)
          cache.get(name) match {
            case Some(ports) =>
              ports() = ServiceCache.CacheEntry(Map.empty, Map.empty)
            case None =>
              cache += (name -> Var(ServiceCache.CacheEntry(Map.empty, Map.empty)))
          }
        }
      case ServiceError(status) =>
        log.error("k8s ns %s service port watch error %s", namespace, status)
    }
  }
}

object ServiceCache {

  case class CacheEntry(ports: Map[String, Address], portMap: Map[Int, Int])

  private def extractPorts(service: Service): CacheEntry = {
    val ports = mutable.Map.empty[String, Address]
    val portMap = mutable.Map.empty[Int, Int]

    for {
      meta <- service.metadata.toSeq
      name <- meta.name.toSeq
      status <- service.status.toSeq
      lb <- status.loadBalancer.toSeq
      spec <- service.spec.toSeq
      port <- spec.ports
    } {
      for {
        ingress <- lb.ingress.toSeq.flatten
        hostname <- ingress.hostname.orElse(ingress.ip)
      } ports += port.name -> Address(new InetSocketAddress(hostname, port.port))

      portMap += (port.targetPort match {
        case Some(targetPort) => port.port -> targetPort
        case None => port.port -> port.port
      })
    }
    CacheEntry(ports.toMap, portMap.toMap)
  }
}
