package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.Ns.ObjectCache
import io.buoyant.k8s.v1._
import java.net.InetSocketAddress

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
)(implicit timer: Timer = DefaultTimer.twitter) extends Namer {

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

  def get(serviceName: String, portName: String): Var[Option[Var[Address]]] = synchronized {
    // we call this unstable because every change to the Address will cause
    // the entire Var[Option[Address]] to update.
    val unstable: Var[Option[Address]] = cache.get(serviceName) match {
      case Some(ports) => ports.map(_.get(portName))
      case None =>
        val ports = Var(Map.empty[String, Address])
        cache += serviceName -> ports
        ports.map(_.get(portName))
    }

    // We can stabilize this by changing the type to Var[Option[Var[Address]]].
    // If this service port is created or deleted, the outer Var will update.
    // If the address of the service port changes, only the inner Var will
    // update.
    val init = unstable.sample().map(Var(_))
    Var.async[Option[VarUp[Address]]](init) { update =>
      // the current inner Var, null if the outer Var is None
      @volatile var addr: VarUp[Address] = null

      unstable.changes.respond {
        case Some(address) if addr == null =>
          // Address created
          addr = Var(address)
          update() = Some(addr)
        case Some(address) =>
          // Address modified
          addr() = address
        case None =>
          // Address deleted
          addr = null
          update() = None
      }
    }
  }

  private[this]type VarUp[T] = Var[T] with Updatable[T]

  private[this] var cache = Map.empty[String, VarUp[Map[String, Address]]]

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
              ports() = Map.empty
            case None =>
              cache += (name -> Var(Map.empty))
          }
        }
      case ServiceError(status) =>
        log.error("k8s ns %s service port watch error %s", namespace, status)
    }
  }
}

object ServiceCache {
  private def extractPorts(service: Service): Map[String, Address] = {
    val ports = for {
      meta <- service.metadata.toSeq
      name <- meta.name.toSeq
      status <- service.status.toSeq
      lb <- status.loadBalancer.toSeq
      ingress <- lb.ingress.toSeq.flatten
      spec <- service.spec.toSeq
      port <- spec.ports
    } yield port.name -> Address(new InetSocketAddress(ingress.ip, port.port))
    ports.toMap
  }
}
