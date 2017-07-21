package io.buoyant.k8s

import java.net.{InetAddress, InetSocketAddress}
import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.namer.Metadata
import scala.collection.{mutable, breakOut}
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
        log.debug("k8s lookup: ns %s service %s label value segment missing for label %s", nsName, serviceName, portName, label)
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
  extends Namer with Stabilize {

  import EndpointsNamer._
  protected[this] val variablePrefixLength: Int

  // memoize port remapping watch activities so that we don't have to
  // create multiple watches on the same `Services` API object.
  private[this] val portRemappings =
    mutable.HashMap[(String, String, Option[String]), Activity[NumberedPortMap]]()

  private[this] val caches =
    mutable.HashMap[(String, String, Option[String]), Activity[EndpointsCache]]()

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
  private[k8s] def numberedPortRemappings(
    nsName: String,
    serviceName: String,
    labelSelector: Option[String]
  ) : Activity[NumberedPortMap] = synchronized {
    def _getPortMap() =
      mkApi(nsName)
        .service(serviceName)
        .activity(_.portMappings, labelSelector = labelSelector) {
          case ((oldMap, v1.ServiceAdded(service))) =>
            val newMap = service.portMappings
            newMap.foreach {
              case ((port, name)) if oldMap.contains(port) =>
                log.debug(s"k8s ns $nsName service $serviceName remapped port $port -> $name")
              case ((port, name)) =>
                log.debug(s"k8s ns $nsName service $serviceName added port mapping $port -> $name")
              }
            oldMap ++ newMap
          case ((oldMap, v1.ServiceModified(service))) =>
            val newMap = service.portMappings
            newMap.foreach {
              case ((port, name)) if oldMap.contains(port) =>
                log.debug(s"k8s ns $nsName service $serviceName remapped port $port -> $name")
              case ((port, name)) =>
                log.debug(s"k8s ns $nsName service $serviceName added port mapping $port -> $name")
            }
            oldMap ++ newMap
          case ((oldMap, v1.ServiceDeleted(service))) =>
            val newMap = service.portMappings
              // log deleted ports
            for { deletedPort <- oldMap.keySet &~ newMap.keySet }
              log.debug(s"k8s ns $nsName service $serviceName deleted port mapping for $deletedPort")
            newMap
          case ((oldMap, v1.ServiceError(error))) =>
            log.error(s"k8s ns $nsName service $serviceName watch error $error")
            oldMap
        }
    portRemappings.getOrElseUpdate((nsName, serviceName, labelSelector), _getPortMap())
  }

  private[k8s] def endpointsCache(
    nsName: String,
    serviceName: String,
    labelSelector: Option[String]
  ): Activity[EndpointsCache] = synchronized {
    def mkCache() =
      mkApi(nsName)
      .endpoints(serviceName)
      .activity(
        EndpointsCache.fromEndpoints(serviceName, nsName),
        labelSelector = labelSelector
      ) { case ((cache, event)) => cache.update(event) }

    caches.getOrElseUpdate((nsName, serviceName, labelSelector), mkCache())
  }

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
    val cache = endpointsCache(nsName, serviceName, labelSelector)
    val unstable = Try(portName.toInt).toOption match {
      case Some(portNumber) =>
        // if `portName` was successfully parsed as an `int`, then
        // we are dealing with a numbered port. we will thus also
        // need the port mappings from the `Service` API response,
        // so join its activity with the endpoints cache activity.
        cache.join(numberedPortRemappings(nsName, serviceName, labelSelector))
          .map { case ((endpoints, ports)) =>
            endpoints.lookupNumberedPort(ports, portNumber)
          }
      case None =>
        // otherwise, we are dealing with a named port, so we can
        // just look up the service from the endpointsCache
        cache.map { endpoints => endpoints.port(portName) }
    }
    stabilize(unstable).map { mkNameTree(id, residual) }
  }
}

object EndpointsNamer {
  val DefaultBackoff: Stream[Duration] =
    Backoff.exponentialJittered(10.milliseconds, 10.seconds)

  protected type PortMap = Map[String, Int]
  protected type NumberedPortMap = Map[Int, String]

  protected case class Endpoint(ip: InetAddress, nodeName: Option[String])

  protected object Endpoint {
    def apply(addr: v1.EndpointAddress): Endpoint =
      Endpoint(InetAddress.getByName(addr.ip), addr.nodeName)
  }

  private[EndpointsNamer] case class EndpointsCache(
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
              port(targetPort)
          }
        }

    def port(portName: String): Option[Set[Address]] =
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
      } yield Address.Inet(isa, nodeName.map(Metadata.nodeName -> _).toMap)
        .asInstanceOf[Address]

    def update(event: v1.EndpointsWatch): EndpointsCache =
      // TODO: i wish this logging code was less ugly...
      event match {
        case v1.EndpointsAdded(update) =>
          val (newEndpoints, newPorts) = update.subsets.toEndpointsAndPorts
          for {endpoint <- newEndpoints if !endpoints.contains(endpoint) }
            log.debug(s"k8s ns $nsName service $serviceName added endpoint $endpoint")
          for { (name, port) <- newPorts if !ports.contains(name) }
            log.debug(s"k8s ns $nsName service $serviceName added port mapping '$name' to port $port")
          this.copy(endpoints = endpoints ++ newEndpoints, ports = ports ++ newPorts)
        case v1.EndpointsModified(update) =>
          val (newEndpoints, newPorts) = update.subsets.toEndpointsAndPorts
          for { endpoint <- endpoints if !newEndpoints.contains(endpoint) } yield {
            log.debug(s"k8s ns $nsName service $serviceName deleted endpoint $endpoint")
            endpoint
          }
          for { name <- ports.keySet if !newPorts.contains(name) } yield {
            log.debug(s"k8s ns $nsName service $serviceName deleted port $name")
            name
          }
          for {endpoint <- newEndpoints }
            if (!endpoints.contains(endpoint))
              log.debug(s"k8s ns $nsName service $serviceName added endpoint $endpoint")
            else
              log.debug(s"k8s ns $nsName service $serviceName modified endpoint $endpoint")
          for { (name, port) <- newPorts }
            if (!ports.contains(name))
              log.debug(s"k8s ns $nsName service $serviceName added port mapping '$name' to port $port")
            else
              log.debug(s"k8s ns $nsName service $serviceName remapped '$name' to port $port")
          this.copy(endpoints = newEndpoints, ports = newPorts)

        case v1.EndpointsDeleted(update) =>
          val (newEndpoints, newPorts) = update.subsets.toEndpointsAndPorts
          val deletedEndpoints =
            for { endpoint <- endpoints if !newEndpoints.contains(endpoint) } yield {
              log.debug(s"k8s ns $nsName service $serviceName deleted endpoint $endpoint")
              endpoint
            }
          val deletedPorts =
            for { name <- ports.keySet if !newPorts.contains(name) } yield {
              log.debug(s"k8s ns $nsName service $serviceName deleted port $name")
              name
            }
          this.copy(
            endpoints = endpoints -- deletedEndpoints,
            ports = ports -- deletedPorts
          )
        case v1.EndpointsError(error) =>
          log.debug(s"k8s ns $nsName service $serviceName endpoints watch error $error")
          this
      }
  }

  private[EndpointsNamer] object EndpointsCache {
    def fromEndpoints(
      nsName: String,
      serviceName: String
    )(endpointsResponse: v1.Endpoints): EndpointsCache = {
      val (endpoints, ports) = endpointsResponse.subsets.toEndpointsAndPorts
      new EndpointsCache(nsName, serviceName, endpoints, ports)
    }
  }

  protected implicit class RichSubsetsSeq(val subsets: Option[Seq[v1.EndpointSubset]]) extends AnyVal {

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

}