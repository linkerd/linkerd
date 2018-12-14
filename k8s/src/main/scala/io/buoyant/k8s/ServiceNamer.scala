package io.buoyant.k8s

import java.net.InetSocketAddress
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.buoyant.ExistentialStability._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Service => _, _}
import com.twitter.util._
import io.buoyant.k8s.v1._
import scala.Function.untupled
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
  import ServiceNamer._

  private[this] val variablePrefixLength = PrefixLen + labelName.size

  // retrieves a memoized activity representing a watch for a
  // (namespace name, service name, label selector), or establishes
  // a new watch activity if one does not yet exist.
  private[this] val service: (String, String, Option[String]) => Activity[Svc] =
    untupled(Memoize[(String, String, Option[String]), Activity[Svc]] {
      case (nsName, serviceName, labelSelector) =>
        val instrumentedAct = mkApi(nsName)
          .service(serviceName)
          .activity(
            Svc.fromResponse(nsName, serviceName),
            labelSelector = labelSelector
          ) { case (svc, event) => svc.update(event) }
        instrumentedAct.underlying
    })

  def lookup(path: Path): Activity[NameTree[Name]] =
    (path.take(variablePrefixLength), labelName) match {
      case (id@Path.Utf8(nsName, portName, serviceName), None) =>
        // "unstable" activity - the activity will update when the existence of
        // the address changes, *or* when the value of the address changes.
        service(nsName.toLowerCase, serviceName.toLowerCase, None)
          .map { _.lookup(portName.toLowerCase) }
          // stabilize the activity by converting it into an
          // `Activity[Option[Var[Address]]]`, where the outer `Activity` will
          // update if the `Option` changes, and the inner `Var` will update on
          // changes to the value of the `Address`.
          .stabilizeExistence
          // convert the contents of the stable activity to a `NameTree`.
          .map(toNameTree(path, _))
      case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
        val labelSelector = Some(s"$label=$labelValue")
        // as above, create an unstable activity, stabilize it, and then
        // convert to a `NameTree`.
        service(nsName.toLowerCase, serviceName.toLowerCase, labelSelector)
          .map { _.lookup(portName.toLowerCase) }
          .stabilizeExistence
          .map(toNameTree(path, _))
      case _ =>
        Activity.value(NameTree.Neg)
    }

  private[this] def toNameTree(
    path: Path,
    svcAddress: Option[Var[Address]]
  ): NameTree[Name.Bound] = svcAddress match {
    case Some(address) =>
      val residual = path.drop(variablePrefixLength)
      val id = path.take(variablePrefixLength)
      val bound = address.map(Addr.Bound(_))
      NameTree.Leaf(Name.Bound(bound, idPrefix ++ id, residual))
    case None =>
      NameTree.Neg
  }

}
private[this] object ServiceNamer {

  val PrefixLen = 3

  def unpackService(service: v1.Service): (Map[String, Address], Map[Int, String]) = {
    val ports = mutable.Map.empty[String, Address]
    val portMap = mutable.Map.empty[Int, String]

    for {
      meta <- service.metadata.toSeq
      name <- meta.name.toSeq
      status <- service.status.toSeq
      lb <- status.loadBalancer.toSeq
      spec <- service.spec.toSeq
      v1.ServicePort(port, targetPort, name) <- spec.ports
    } {
      for {
        ingress <- lb.ingress.toSeq.flatten
        hostname <- ingress.hostname.orElse(ingress.ip)
      } ports += name -> Address(new InetSocketAddress(hostname, port))

      portMap += (targetPort match {
        case Some(target) => port -> target
        case None => port -> port.toString
      })
    }
    (ports.toMap, portMap.toMap)
  }

  /**
   * Internal representation of a Kubernetes service as a map of port names
   * to `Address`es and a map of port numbers to port names.
   * @param ports a map of `String`s representing port names to `Address`es.
   * @param portMappings a map of port numbers to port names.
   */
  case class Svc(
    nsName: String,
    serviceName: String,
    ports: Map[String, Address],
    portMappings: Map[Int, String]
  ) {
    val portLogger = PortMapLogger(nsName, serviceName)

    /**
     * Look up the port named `portName` and return the corresponding
     * `Address`, if it exists.
     * @param portName the port name to look up.
     * @return `None` if no port named `portName` exists, `Some(Address)`
     *        a port was found.
     */
    def lookup(portName: String): Option[Address] =
      Try(portName.toInt).toOption match {
        // if the port name could be parsed as an integer, look up a
        // numbered port.
        case Some(portNumber) => lookupNumberedPort(portNumber)
        // otherwise, look up a named port.
        case None => lookupNamedPort(portName)
      }

    private[this] def lookupNamedPort(portName: String): Option[Address] =
      ports.get(portName)

    private[this] def lookupNumberedPort(portNumber: Int): Option[Address] =
      for {
        portName <- portMappings.get(portNumber)
        address <- ports.get(portName)
      } yield address

    @inline
    private[this] def newState(service: v1.Service): Svc = {
      val (newPorts, newMappings) = unpackService(service)
      portLogger.logDiff(ports, newPorts)
      portLogger.logDiff(portMappings, newMappings)
      this.copy(ports = newPorts, portMappings = newMappings)
    }

    /**
     * Update this `Svc` with a [[v1.ServiceWatch]] watch event
     * @param event the [[v1.ServiceWatch]] watch event that occurred.
     * @return an updated `Svc` representing the watched service.
     */
    def update(event: v1.ServiceWatch): Svc =
      event match {
        case v1.ServiceAdded(s) =>
          log.debug("k8s ns %s service %s added", nsName, serviceName)
          newState(s)
        case v1.ServiceModified(s) =>
          log.debug("k8s ns %s service %s modified", nsName, serviceName)
          newState(s)
        case v1.ServiceDeleted(_) =>
          log.debug("k8s ns %s service %s deleted", nsName, serviceName)
          this.copy(ports = Map.empty, portMappings = Map.empty)
        case v1.ServiceError(error) =>
          log.warning(
            "k8s ns %s service %s error %s",
            nsName, serviceName, error
          )
          this
      }
  }

  object Svc {
    /**
     * Creates a new [[Svc]] from a services API response.
     * @param response an `Option` containing either a [[v1.Service]] API
     *                 response, or `None` if the service does not exist.
     * @return either a [[Svc]] populated by the service API response, if
     *         the service exists, or a [[Svc]] with empty ports and port
     *         mappings maps if the service does not exist.
     */
    def fromResponse(nsName: String, serviceName: String)(response: Option[v1.Service]): Svc =
      response match {
        case Some(service: v1.Service) =>
          val (ports, mappings) = unpackService(service)
          Svc(nsName, serviceName, ports, mappings)
        case None =>
          Svc(nsName, serviceName, Map.empty, Map.empty)
      }
  }
}
