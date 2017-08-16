package io.buoyant.k8s

import java.net.{InetAddress, InetSocketAddress}
import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.namer.Metadata
import scala.collection.breakOut
import scala.language.implicitConversions

class MultiNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => v1.NsApi,
  backoff: Stream[Duration] = EndpointsNamer.DefaultBackoff
)(implicit timer: Timer = DefaultTimer)
  extends EndpointsNamer(idPrefix, mkApi, labelName, backoff)(timer) {

  protected[this] override val variablePrefixLength: Int =
    MultiNsNamer.PrefixLen + labelName.size

  /**
   * Accepts names in the form:
   *   /<namespace>/<port-name>/<svc-name>[/<label-value>]/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * kubernetes master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val lowercasePath = path.take(variablePrefixLength) match {
      case Path.Utf8(segments@_*) => Path.Utf8(segments.map(_.toLowerCase): _*)
    }
    (lowercasePath, labelName) match {
      case (id@Path.Utf8(nsName, portName, serviceName), None) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s", id.show, path.show)
        lookupServices(nsName, portName, serviceName, id, residual)

      case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
        val labelSelector = Some(s"$label=$labelValue")
        lookupServices(nsName, portName, serviceName, id, residual, labelSelector)

      case (id@Path.Utf8(nsName, portName, serviceName), Some(label)) =>
        log.debug("k8s lookup: ns %s service %s label value segment missing for label %s",
                  nsName, serviceName, portName, label)
        Activity.value(NameTree.Neg)
      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}

object MultiNsNamer {
  protected val PrefixLen = 3
}

class SingleNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  nsName: String,
  mkApi: String => v1.NsApi,
  backoff: Stream[Duration] = EndpointsNamer.DefaultBackoff
)(implicit timer: Timer = DefaultTimer)
  extends EndpointsNamer(idPrefix, mkApi, labelName, backoff)(timer) {

  protected[this] override val variablePrefixLength: Int =
    SingleNsNamer.PrefixLen + labelName.size

  /**
   * Accepts names in the form:
   *   /<port-name>/<svc-name>[/<label-value>]/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * kubernetes master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val lowercasePath = path.take(variablePrefixLength) match {
      case Path.Utf8(segments@_*) => Path.Utf8(segments.map(_.toLowerCase): _*)
    }
    (lowercasePath, labelName) match {
      case (id@Path.Utf8(portName, serviceName), None) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s", id.show, path.show)
        lookupServices(nsName, portName, serviceName, id, residual)

      case (id@Path.Utf8(portName, serviceName, labelValue), Some(label)) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
        val labelSelector = Some(s"$label=$labelValue")
        lookupServices(nsName, portName, serviceName, id, residual, labelSelector)

      case (id@Path.Utf8(portName, serviceName), Some(label)) =>
        log.debug(
          "k8s lookup: ns %s service %s label value segment missing for label %s",
          nsName, serviceName, portName, label
        )
        Activity.value(NameTree.Neg)

      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}

object SingleNsNamer {
  protected val PrefixLen = 2
}

abstract class EndpointsNamer(
  idPrefix: Path,
  mkApi: String => v1.NsApi,
  labelName: Option[String] = None,
  backoff: Stream[Duration] = EndpointsNamer.DefaultBackoff
)(implicit timer: Timer = DefaultTimer)
  extends Namer {

  import EndpointsNamer._
  protected[this] val variablePrefixLength: Int

  // memoize port remapping watch activities so that we don't have to
  // create multiple watches on the same `Services` API object.
  private[this] val portRemappingsMemo =
    Memoize[(String, String, Option[String]), Activity[NumberedPortMap]] {
      case (nsName, serviceName, labelSelector) =>
        val logEvent = EventLogger(nsName, serviceName)
        mkApi(nsName)
          .service(serviceName)
          .activity(_.portMappings, labelSelector = labelSelector) {
            case (oldMap, v1.ServiceAdded(service)) =>
              val newMap = service.portMappings
              logEvent.addition(newMap -- oldMap.keySet)
              oldMap ++ newMap
            case (oldMap, v1.ServiceModified(service)) =>
              val newMap = service.portMappings
              logEvent.addition(newMap -- oldMap.keySet)
              logEvent.deletion(oldMap -- newMap.keySet)
              val keptPorts = newMap.keySet &~ oldMap.keySet
              val remappedPorts =
                oldMap.filterKeys(keptPorts.contains)
                  .zip(newMap.filterKeys(keptPorts.contains))
                  .filter{ case ((_, a), (_, b)) => a != b }
              logEvent.modification(remappedPorts)
              oldMap ++ newMap
            case (oldMap, v1.ServiceDeleted(service)) =>
              val newMap = service.portMappings
              logEvent.deletion(oldMap -- newMap.keySet)
              newMap
            case (oldMap, v1.ServiceError(error)) =>
              log.error(
                "k8s ns %s service %s watch error %s",
                nsName, serviceName, error
              )
              oldMap
        }
  }

  /**
   * Watch the numbered-port remappings for the service named `serviceName`
   * in the namespace named `nsName`.
   * @param nsName the name of the Kubernetes namespace.
   * @param serviceName the name of the Kubernetes service.
   * @return an `Activity` containing a `Map[Int, String]` representing the
   *         port number to port name mappings
   * @note that the corresponding `Activity` instances are cached so we don't
   *       create multiple watches on the same Kubernetes objects – meaning,
   *       if you look up the port remappings in a given (nsName, serviceName)
   *       pair multiple times, you will always get back the same `Activity`,
   *       which is created the first time that pair is looked up.
   */
  private[this] def numberedPortRemappings(
    nsName: String,
    serviceName: String,
    labelSelector: Option[String]
  ) = portRemappingsMemo((nsName, serviceName, labelSelector))

  private[this] val serviceEndpointsMemo =
    Memoize[(String, String, Option[String]), Activity[ServiceEndpoints]]  {
      case (nsName, serviceName, labelSelector) =>
        mkApi(nsName)
          .endpoints(serviceName)
          .activity(
            ServiceEndpoints.fromEndpoints(serviceName, nsName),
            labelSelector = labelSelector
          ) { case (cache, event) => cache.update(event) }
    }

  private[this] def serviceEndpoints(
    nsName: String,
    serviceName: String,
    labelSelector: Option[String]
  ): Activity[ServiceEndpoints] =
    serviceEndpointsMemo((nsName, serviceName, labelSelector))

  @inline private[this] def mkNameTree(
    id: Path,
    residual: Path
  )(lookup: Option[Var[Set[Address]]]): NameTree[Name] = lookup match {
    case Some(addresses) =>
      val addrs = addresses.map { Addr.Bound(_) }
      NameTree.Leaf(Name.Bound(addrs, idPrefix ++ id, residual))
    case None => NameTree.Neg
  }

  private[k8s] def lookupServices(
    nsName: String,
    portName: String,
    serviceName: String,
    id: Path,
    residual: Path,
    labelSelector: Option[String] = None
  ): Activity[NameTree[Name]] = {
    val endpointsAct = serviceEndpoints(nsName, serviceName, labelSelector)
    val unstable = Try(portName.toInt).toOption match {
      case Some(portNumber) =>
        // if `portName` was successfully parsed as an `int`, then
        // we are dealing with a numbered port. we will thus also
        // need the port mappings from the `Service` API response,
        // so join its activity with the endpoints activity.
        endpointsAct.join(numberedPortRemappings(nsName, serviceName, labelSelector))
          .map { case (endpoints, ports) =>
            endpoints.lookupNumberedPort(ports, portNumber)
          }
      case None =>
        // otherwise, we are dealing with a named port, so we can
        // just look up the service from the endpoints activity
        endpointsAct.map { endpoints => endpoints.lookupNamedPort(portName) }
    }
    stabilize(unstable).map { mkNameTree(id, residual) }
  }
}

object EndpointsNamer {
  val DefaultBackoff: Stream[Duration] =
    Backoff.exponentialJittered(10.milliseconds, 10.seconds)

  protected type PortMap = Map[String, Int]
  protected type NumberedPortMap = Map[Int, String]

  private trait Loggable {
    def logAction(verb: String)(nsName: String, serviceName: String): Unit
    val logAddition: (String, String) => Unit
    = logAction("added")
    val logDeletion: (String, String) => Unit
    = logAction("deleted")
  }

  private case class Endpoint(ip: InetAddress, nodeName: Option[String])
  extends Loggable {
    override def logAction(
      verb: String
    )(
      nsName: String,
      serviceName: String
    ): Unit =
      log.debug(
        "k8s ns %s service %s %s endpoint %s",
        nsName, serviceName, verb, this
      )
  }

  private object Endpoint {
    def apply(addr: v1.EndpointAddress): Endpoint =
      Endpoint(InetAddress.getByName(addr.ip), addr.nodeName)
  }

  private[EndpointsNamer] case class ServiceEndpoints(
    nsName: String,
    serviceName: String,
    endpoints: Set[Endpoint],
    ports: PortMap
  ) {

    def lookupNumberedPort(
      mappings: NumberedPortMap,
      portNumber: Int
    ): Option[Set[Address]] =
      mappings.get(portNumber)
        .flatMap { targetPort =>
          // target may be an int (port number) or string (port name)
          Try(targetPort.toInt).toOption match {
            case Some(targetPortNumber) =>
              // target port is a number and therefore exists
              Some(port(targetPortNumber))
            case None =>
              // target port is a name and may or may not exist
              lookupNamedPort(targetPort)
          }
        }

    def lookupNamedPort(portName: String): Option[Set[Address]] =
        ports.get(portName).map { portNumber =>
          for {
            Endpoint(ip, nodeName) <- endpoints
            isa = new InetSocketAddress(ip, portNumber)
          } yield Address.Inet(isa, nodeName.map(Metadata.nodeName -> _).toMap)
            .asInstanceOf[Address]
        }

    def port(portNumber: Int): Set[Address] =
      for {
        Endpoint(ip, nodeName) <- endpoints
        isa = new InetSocketAddress(ip, portNumber)
      } yield Address.Inet(isa, nodeName.map(Metadata.nodeName -> _).toMap): Address

    private[this] val logEvent = EventLogger(serviceName, nsName)
    def update(event: v1.EndpointsWatch): ServiceEndpoints =
      // TODO: i wish this logging code was less ugly...
      event match {
        case v1.EndpointsAdded(update) =>
          val (newEndpoints, newPorts: Map[String, Int]) =
              update.subsets.toEndpointsAndPorts
          logEvent.addition(newEndpoints -- endpoints)
          logEvent.addition(newPorts -- ports.keySet)
          this.copy(
            endpoints = endpoints ++ newEndpoints,
            ports = ports ++ newPorts
          )
        case v1.EndpointsModified(update) =>
          val (newEndpoints, newPorts: Map[String, Int]) =
            update.subsets.toEndpointsAndPorts
          logEvent.addition(newEndpoints -- endpoints)
          logEvent.deletion(endpoints -- endpoints)
          logEvent.addition(newPorts -- ports.keySet)
          logEvent.deletion(ports -- newPorts.keySet)
          val keptPorts = newPorts.keySet &~ ports.keySet
          val remappedPorts =
            ports.filterKeys(keptPorts.contains)
              .zip(newPorts.filterKeys(keptPorts.contains))
              .filter{ case ((_, a), (_, b)) => a != b }
          logEvent.modification(remappedPorts)
          this.copy(endpoints = newEndpoints, ports = newPorts)

        case v1.EndpointsDeleted(update) =>
          val (newEndpoints, newPorts: Map[String, Int]) =
            update.subsets.toEndpointsAndPorts
          val deletedEndpoints = endpoints.diff(newEndpoints)
          val deletedPorts = ports.filterKeys(!newPorts.contains(_))
          logEvent.deletion(deletedEndpoints)
          logEvent.deletion(deletedPorts)
          this.copy(
            endpoints = endpoints -- deletedEndpoints,
            ports = ports -- deletedPorts.keySet
          )
        case v1.EndpointsError(error) =>
          log.debug("k8s ns %s service %s endpoints watch error %s", nsName, serviceName, error)
          this
      }
  }

  private[EndpointsNamer] object ServiceEndpoints {
    def fromEndpoints(
      nsName: String,
      serviceName: String
    )(endpointsResponse: v1.Endpoints): ServiceEndpoints = {
      val (endpoints, ports) = endpointsResponse.subsets.toEndpointsAndPorts
      ServiceEndpoints(nsName, serviceName, endpoints, ports)
    }
  }

  private implicit class RichSubsetsSeq(
    val subsets: Option[Seq[v1.EndpointSubset]]
  ) extends AnyVal {

    private[this] def toPortMap(subset: v1.EndpointSubset): PortMap =
      (for {
        v1.EndpointPort(port, Some(name), maybeProto) <- subset.portsSeq
        if maybeProto.map(_.toUpperCase).getOrElse("TCP") == "TCP"
      } yield name -> port)(breakOut)

    private[this] def toEndpointSet(subset: v1.EndpointSubset): Set[Endpoint] =
      for {address: v1.EndpointAddress <- subset.addressesSeq.toSet} yield {
        Endpoint(address)
      }

    def toEndpointsAndPorts: (Set[Endpoint], PortMap) = {
      val result = for {
        subsetsSeq <- subsets.toSeq
        subset <- subsetsSeq
      } yield {
        (toEndpointSet(subset), toPortMap(subset))
      }
      val (endpoints, ports) = result.unzip
      (endpoints.flatten.toSet, if (ports.isEmpty) Map.empty else ports.reduce(_ ++ _))
    }
  }

  private[EndpointsNamer] implicit class LoggableMapping[A, B](val mapping: (A, B))
  extends Loggable {
    override def logAction(verb: String)(nsName: String, serviceName: String): Unit =
      log.debug(
        "k8s ns %s service %s %s port mapping %s to %s",
        nsName, serviceName, verb, mapping._1, mapping._2
      )
  }

  private case class EventLogger(nsName: String, serviceName: String) {
    def addition(additions: Iterable[Loggable]): Unit =
      additions.foreach(_.logAddition(nsName, serviceName))

    def deletion(deletions: Iterable[Loggable]): Unit =
      deletions.foreach(_.logDeletion(nsName, serviceName))

    def modification[T <: Loggable](mods: Iterable[(T, T)]): Unit =
      for { (old, replacement) <- mods }
        log.debug(
          "k8s ns %s service %s replaced %s with %s",
          nsName, serviceName, old, replacement
        )

    def addition(additions: Map[_, _]): Unit =
      additions.foreach(_.logAddition(nsName, serviceName))

    def deletion(deletions: Map[_, _]): Unit =
      deletions.foreach(_.logDeletion(nsName, serviceName))

    def modification[A, B](mods: Map[(A, B), (A, B)]): Unit =
      for { (old, replacement) <- mods }
        log.debug(
          "k8s ns %s service %s %s remapped port %s from %s to %s",
          nsName, serviceName, old._1, old._2, replacement._2
        )
  }

}