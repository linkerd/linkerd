package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
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
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer.twitter) extends Namer {

  private[this] val PrefixLen = 3

  override def lookup(path: Path): Activity[NameTree[Name]] = path.take(PrefixLen) match {
    case id@Path.Utf8(nsName, portName, serviceName) =>

      val nameTree = serviceNs.get(nsName).get(serviceName, portName).map {
        case Some(address) =>
          val bound = address.map(Addr.Bound(_))
          NameTree.Leaf(Name.Bound(bound, idPrefix ++ id, path.drop(PrefixLen)))
        case None =>
          NameTree.Neg
      }
      Activity(nameTree.map(Activity.Ok(_)))

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] def getPort(service: Service, portName: String): Option[Int] =
    for {
      spec <- service.spec
      port <- spec.ports.find(_.name == portName)
    } yield port.port

  val serviceNs = new Ns[Service, ServiceWatch, ServiceList, ServiceCache](backoff, timer) {
    override protected def mkResource(name: String): NsListResource[Service, ServiceWatch, ServiceList] =
      mkApi(name).services

    override protected def mkCache(name: String): ServiceCache =
      new ServiceCache(name)

    override protected def update(cache: ServiceCache)(event: ServiceWatch): Unit =
      cache.update(event)

    override protected def initialize(
      cache: ServiceCache,
      list: ServiceList
    ): Unit = cache.initialize(list)
  }
}

class ServiceCache(namespace: String) {

  def get(serviceName: String, portName: String): Var[Option[Var[Address]]] = synchronized {
    // we call this unstable because every change to the Address will cause
    // the entire Var[Option[Address]] to update.
    val unstable = cache.get(serviceName) match {
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

  private[this] def extractPorts(service: Service): Map[String, Address] = {
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

  def initialize(list: ServiceList): Unit = synchronized {
    val services = for {
      service <- list.items
      meta <- service.metadata
      name <- meta.name
    } yield name -> Var(extractPorts(service))
    cache = services.toMap
  }

  def update(watch: ServiceWatch): Unit = synchronized {
    watch match {
      case ServiceAdded(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          log.info("k8s added service: %s", name)
          cache.get(name) match {
            case Some(ports) =>
              ports() = extractPorts(service)
            case None =>
              cache += (name -> Var(extractPorts(service)))
          }
        }
      case ServiceModified(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          cache.get(name) match {
            case Some(ports) =>
              log.info("k8s modified service: %s", name)
              ports() = extractPorts(service)
            case None =>
              log.warning("k8s received modified watch for unknown service %s", name)
          }
        }
      case ServiceDeleted(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          log.debug("k8s deleted service : %s", name)
          cache.get(name) match {
            case Some(ports) =>
              ports() = Map.empty
            case None =>
              cache += (name -> Var(Map.empty))
          }
        }
      case ServiceError(status) =>
        log.error("k8s service port watch error %s", status)
    }
  }
}
