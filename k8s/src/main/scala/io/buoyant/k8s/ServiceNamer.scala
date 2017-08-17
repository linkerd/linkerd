package io.buoyant.k8s

import java.net.InetSocketAddress
import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.v1._
import scala.collection.{breakOut, mutable}

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

  private[this] case class Svc(
    ports: Map[String, Address],
    portMappings: Map[Int, String]
  ) {
    def lookup(portName: String): Option[Address] =
      Try(portName.toInt).toOption match {
        case Some(portNumber) => lookupNumberedPort(portNumber)
        case None => lookupNamedPort(portName)
      }

    def lookupNamedPort(portName: String): Option[Address] =
      ports.get(portName)

    def lookupNumberedPort(portNumber: Int): Option[Address] =
      for {
        portName <- portMappings.get(portNumber)
        address <- ports.get(portName)
      } yield address

    def update(logEvent: EventLogger, event: v1.ServiceWatch): Svc = event match {
      case v1.ServiceAdded(service) =>
        val svc = Svc(service)
        logEvent.addition(svc.portMappings -- portMappings.keys)
        logEvent.addition(svc.ports -- ports.keys)
        svc
      case v1.ServiceModified(service) =>
        val svc = Svc(service)
        logEvent.addition(svc.portMappings -- portMappings.keys)
        logEvent.addition(svc.ports -- ports.keys)
        logEvent.deletion(portMappings -- svc.portMappings.keys)
        logEvent.deletion(ports -- svc.ports.keys)
        val keptMappings = portMappings.keySet.intersect(svc.portMappings.keySet)
        val keptPorts =  ports.keySet.intersect(svc.ports.keySet)
        val modifiedMappings =
          portMappings.filterKeys(keptMappings.contains)
                      .zip(svc.portMappings.filterKeys(keptMappings.contains))
                      .filter{ case ((_, a), (_, b)) => a != b }
        val modifiedPorts =
          ports.filterKeys(keptPorts.contains)
            .zip(svc.ports.filterKeys(keptPorts.contains))
            .filter{ case ((_, a), (_, b)) => a != b }
        logEvent.modification(modifiedMappings)
        logEvent.modification(modifiedPorts)
        svc
      case v1.ServiceDeleted(deleted) =>
        val Svc(deletedPorts, deletedMappings) = Svc(deleted)
        logEvent.deletion(deletedPorts)
        logEvent.deletion(deletedMappings)
        this.copy(
          ports = ports -- deletedPorts.keys,
          portMappings = portMappings -- deletedMappings.keys
        )
      case v1.ServiceError(error) =>
        log.warning("k8s ns %s service %s error %s", logEvent.nsName, logEvent.serviceName, error)
        this
    }
  }

  private[this] object Svc {
    def apply(service: v1.Service): Svc = {
      val ports = mutable.Map.empty[String, Address]
      val portMap = mutable.Map.empty[Int, String]

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
          case None => port.port -> port.port.toString
        })
      }
      Svc(ports.toMap, portMap.toMap)
    }
    def fromResponse(response: Option[v1.Service]): Svc =
      response.map(Svc(_)).getOrElse(Svc(Map.empty, Map.empty))
  }

  private[this] val PrefixLen = 3
  private[this] val variablePrefixLength = PrefixLen + labelName.size

  private[this] val servicesMemo =
    Memoize[(String, String, Option[String]), Activity[Svc]] {
      case (nsName, serviceName, labelSelector) =>
        val eventLogger = EventLogger(nsName, serviceName)
        mkApi(nsName)
          .service(serviceName)
          .activity(
            Svc.fromResponse(_),
            labelSelector = labelSelector
          ) { case (svc, event) => svc.update(eventLogger, event) }
    }

  private[this] def service(
    nsName: String,
    serviceName: String,
    labelSelector: Option[String] = None
  ): Activity[Svc] =
    servicesMemo((nsName, serviceName, labelSelector))

  @inline private[this] def mkNameTree(
    id: Path,
    residual: Path
  )(lookup: Option[Var[Set[Address]]]): NameTree[Name] = lookup match {
    case Some(addresses) =>
      val addrs = addresses.map {
        Addr.Bound(_)
      }
      NameTree.Leaf(Name.Bound(addrs, idPrefix ++ id, residual))
    case None => NameTree.Neg
  }

  def lookup(path: Path): Activity[NameTree[Name]] =
    (path.take(variablePrefixLength), labelName) match {
      case (id@Path.Utf8(nsName, portName, serviceName), None) =>
        val unstable = service(nsName.toLowerCase, serviceName.toLowerCase)
          .map { _.lookup(portName.toLowerCase) }
        stabilize(unstable).map(addr => toNameTree(path, addr))
      case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
        val labelSelector = Some(s"$label=$labelValue")
        val unstable = service(
          nsName.toLowerCase,
          serviceName.toLowerCase,
          labelSelector
        ).map { _.lookup(portName.toLowerCase) }
        stabilize(unstable).map(addr => toNameTree(path, addr))
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

}