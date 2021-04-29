package io.buoyant.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import java.net.{InetAddress, InetSocketAddress}
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.buoyant.ExistentialStability._
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.namer.{InstrumentedActivity, Metadata}
import scala.collection.{breakOut, mutable}
import scala.language.implicitConversions

class MultiNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => v1.NsApi,
  backoff: Backoff = EndpointsNamer.DefaultBackoff
)(implicit timer: Timer = DefaultTimer)
  extends EndpointsNamer(idPrefix, mkApi, labelName, backoff)(timer) {

  private[this] val variablePrefixLength: Int =
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
        log.debug(
          "k8s lookup: ns %s service %s label value segment missing for label %s",
          nsName, serviceName, label
        )
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
  backoff: Backoff = EndpointsNamer.DefaultBackoff
)(implicit timer: Timer = DefaultTimer)
  extends EndpointsNamer(idPrefix, mkApi, labelName, backoff)(timer) {

  private[this] val variablePrefixLength: Int =
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
          nsName, serviceName, label
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
  backoff: Backoff = EndpointsNamer.DefaultBackoff
)(implicit timer: Timer = DefaultTimer)
  extends Namer with Admin.WithHandlers {
  import EndpointsNamer._

  // Metadata about the state of portMap watches.
  private[this] val portMapInstrumentation = mutable.Map[ServiceKey, InstrumentedPortMap]()

  /**
   * Watch the numbered-port remappings for the service named `serviceName`
   * in the namespace named `nsName`.
   * @param ns the name of the Kubernetes namespace.
   * @param srv the name of the Kubernetes service.
   * @return an `Activity` containing a `Map[Int, String]` representing the
   *         port number to port name mappings
   * @note that the corresponding `Activity` instances are cached so we don't
   *       create multiple watches on the same Kubernetes objects – meaning,
   *       if you look up the port remappings in a given (nsName, serviceName)
   *       pair multiple times, you will always get back the same `Activity`,
   *       which is created the first time that pair is looked up.
   */
  private[this] val numberedPortRemappings: ServiceKey => Activity[NumberedPortMap] =
    Memoize[ServiceKey, Activity[NumberedPortMap]] {
      // memoize port remapping watch activities so that we don't have to
      // create multiple watches on the same `Services` API object.
      case key@ServiceKey(nsName, serviceName, labelSelector) =>
        val portLogger = PortMapLogger(nsName, serviceName)
        val watchState = new WatchState[v1.Service, v1.ServiceWatch]()
        val instrumentedAct = mkApi(nsName)
          .service(serviceName)
          .activity(
            _.map(_.portMappings).getOrElse(Map.empty),
            labelSelector = labelSelector,
            watchState = Some(watchState)
          ) {
              case (oldMap, v1.ServiceAdded(service)) =>
                val newMap = service.portMappings
                portLogger.logDiff(oldMap, newMap)
                newMap
              case (oldMap, v1.ServiceModified(service)) =>
                val newMap = service.portMappings
                portLogger.logDiff(oldMap, newMap)
                newMap
              case (oldMap, v1.ServiceDeleted(_)) =>
                log.debug(
                  "k8s ns %s service %s deleted",
                  nsName, serviceName
                )
                Map.empty
              case (oldMap, v1.ServiceError(error)) =>
                log.warning(
                  "k8s ns %s service %s watch error %s",
                  nsName, serviceName, error
                )
                oldMap
            }
        portMapInstrumentation(key) = InstrumentedPortMap(instrumentedAct, watchState)
        instrumentedAct.underlying
    }

  // Metadata about the state of endpoint watches.
  private[this] val endpointsInstrumentation = mutable.Map[ServiceKey, InstrumentedEndpoints]()

  private[this] val serviceEndpoints: ServiceKey => Activity[ServiceEndpoints] =
    Memoize[ServiceKey, Activity[ServiceEndpoints]] {
      case key@ServiceKey(nsName, serviceName, labelSelector) =>
        val watchState = new WatchState[v1.Endpoints, v1.EndpointsWatch]()
        val instrumentedAct = mkApi(nsName)
          .endpoints(serviceName)
          .activity(
            ServiceEndpoints.fromResponse(nsName, serviceName),
            labelSelector = labelSelector,
            watchState = Some(watchState)
          ) { case (cache, event) => cache.update(event) }
        endpointsInstrumentation(key) = InstrumentedEndpoints(instrumentedAct, watchState)
        instrumentedAct.underlying
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

  def adminHandlers = {
    val prefix = idPrefix.drop(1).show.drop(1) // drop leading "/#/"
    Seq(Admin.Handler(
      s"/namer_state/$prefix.json",
      new EndpointsNamerStateHandler(portMapInstrumentation, endpointsInstrumentation)
    ))
  }

  private[k8s] def lookupServices(
    nsName: String,
    portName: String,
    serviceName: String,
    id: Path,
    residual: Path,
    labelSelector: Option[String] = None
  ): Activity[NameTree[Name]] = {
    val key = ServiceKey(nsName, serviceName, labelSelector)
    val endpointsAct = serviceEndpoints(key)
    // create an "unstable" activity - this will update if the existence
    // of the set of `Address`es changes *or* the values of the `Address`es
    // change.
    val unstable = Try(portName.toInt).toOption match {
      case Some(portNumber) =>
        // if `portName` was successfully parsed as an `int`, then
        // we are dealing with a numbered port. we will thus also
        // need the port mappings from the `Service` API response,
        // so join its activity with the endpoints activity.
        endpointsAct
          .join(numberedPortRemappings(key))
          .map {
            case (endpoints, ports) =>
              endpoints.lookupNumberedPort(ports, portNumber)
          }
      case None =>
        // otherwise, we are dealing with a named port, so we can
        // just look up the service from the endpoints activity
        endpointsAct.map { endpoints => endpoints.lookupNamedPort(portName) }
    }
    // stabilize the activity by converting it into an
    // `Activity[Option[Var[Set[Address]]]]`, where the outer `Activity` will
    // update if the `Option` changes, and the inner `Var` will update on
    // changes to the value of the set of `Address`es.
    unstable.stabilizeExistence
      // convert the contents of the stable activity to a `NameTree`.
      .map { mkNameTree(id, residual) }
  }

}

object EndpointsNamer {
  val DefaultBackoff: Backoff =
    Backoff.exponentialJittered(10.milliseconds, 10.seconds)

  private[EndpointsNamer] case class ServiceKey(
    nsName: String,
    serviceName: String,
    labelSelector: Option[String]
  )

  private[EndpointsNamer] case class InstrumentedPortMap(
    act: InstrumentedActivity[NumberedPortMap],
    watch: WatchState[v1.Service, v1.ServiceWatch]
  )

  private[EndpointsNamer] case class InstrumentedEndpoints(
    act: InstrumentedActivity[ServiceEndpoints],
    watch: WatchState[v1.Endpoints, v1.EndpointsWatch]
  )

  protected type PortMap = Map[String, Int]
  protected type NumberedPortMap = Map[Int, String]

  private[EndpointsNamer] case class Endpoint(ip: InetAddress, nodeName: Option[String])

  private[EndpointsNamer] object Endpoint {
    def apply(addr: v1.EndpointAddress): Endpoint =
      Endpoint(InetAddress.getByName(addr.ip), addr.nodeName)
  }

  private[EndpointsNamer] implicit class RichSubsetsSeq(
    val subsets: Option[Seq[v1.EndpointSubset]]
  ) extends AnyVal {

    private[this] def toPortMap(subset: v1.EndpointSubset): PortMap =
      (for {
        v1.EndpointPort(port, Some(name), maybeProto) <- subset.portsSeq
        if maybeProto.map(_.toUpperCase).getOrElse("TCP") == "TCP"
      } yield name -> port)(breakOut)

    private[this] def toEndpointSet(subset: v1.EndpointSubset): Set[Endpoint] =
      for { address: v1.EndpointAddress <- subset.addressesSeq.toSet } yield {
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

  private[EndpointsNamer] object Subsets {
    def unapply(endpoints: v1.Endpoints): Option[(Set[Endpoint], PortMap)] =
      Some(endpoints.subsets.toEndpointsAndPorts)
  }

  private[EndpointsNamer] case class ServiceEndpoints(
    nsName: String,
    serviceName: String,
    endpoints: Set[Endpoint],
    ports: PortMap
  ) {
    @JsonIgnore
    private[this] val portLogger = PortMapLogger(nsName, serviceName)
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

    @inline
    private[this] def newState(e: v1.Endpoints): ServiceEndpoints = {
      val (newEndpoints, newPorts) = e.subsets.toEndpointsAndPorts
      // log new endpoints
      if (log.isLoggable(Logger.TRACE)) {
        // log port mapping changes
        // log endpoints changes
        (endpoints -- newEndpoints).foreach {
          log.trace(
            "k8s ns %s service %s removed %s",
            nsName, serviceName, _
          )
        }
        (newEndpoints -- endpoints).foreach {
          log.trace(
            "k8s ns %s service %s added %s",
            nsName, serviceName, _
          )
        }
      }
      // log new ports
      portLogger.logDiff(ports, newPorts)
      this.copy(endpoints = newEndpoints, ports = newPorts)
    }

    def update(event: v1.EndpointsWatch): ServiceEndpoints =
      event match {
        case v1.EndpointsAdded(e) =>
          log.debug("k8s ns %s service %s added endpoints", nsName, serviceName)
          newState(e)
        case v1.EndpointsModified(e) =>
          log.debug("k8s ns %s service %s modified endpoints", nsName, serviceName)
          newState(e)
        case v1.EndpointsDeleted(_) =>
          log.debug("k8s ns %s service %s deleted endpoints", nsName, serviceName)
          this.copy(endpoints = Set.empty, ports = Map.empty)
        case v1.EndpointsError(error) =>
          log.warning(
            "k8s ns %s service %s endpoints watch error %s",
            nsName, serviceName, error
          )
          this
      }

  }

  private[EndpointsNamer] object ServiceEndpoints {
    def fromEndpoints(
      nsName: String,
      serviceName: String
    )(
      endpoints: v1.Endpoints
    ): ServiceEndpoints = {
      val (endpointsSet, ports) = endpoints.subsets.toEndpointsAndPorts
      ServiceEndpoints(nsName, serviceName, endpointsSet, ports)
    }
    @inline def fromResponse(
      nsName: String,
      serviceName: String
    )(
      resp: Option[v1.Endpoints]
    ): ServiceEndpoints =
      resp.map { fromEndpoints(nsName, serviceName) }
        .getOrElse {
          log.warning(
            "k8s ns %s service %s endpoints resource does not exist, " +
              "assuming it has yet to be created",
            nsName, serviceName
          )
          ServiceEndpoints(nsName, serviceName, Set.empty, Map.empty)
        }
  }

  /**
   * An admin handler that exposes the internal state of Kubernetes watches.
   */
  private[EndpointsNamer] class EndpointsNamerStateHandler(
    services: mutable.Map[ServiceKey, InstrumentedPortMap],
    endpoints: mutable.Map[ServiceKey, InstrumentedEndpoints]
  ) extends Service[Request, Response] {
    private[this] val mapper = Parser.jsonObjectMapper(Nil)

    override def apply(request: Request): Future[Response] = {
      val servicesState = services.map {
        case (ServiceKey(ns, svc, label), InstrumentedPortMap(act, watchState)) =>
          val labelStr = label match {
            case Some(s) => s":$s"
            case None => ""
          }
          s"${ns}/${svc}${labelStr}" -> Map(
            "state" -> act.stateSnapshot(),
            "watch" -> watchState
          )
      }
      val endpointsState = endpoints.map {
        case (ServiceKey(ns, svc, label), InstrumentedEndpoints(act, watchState)) =>
          val labelStr = label match {
            case Some(s) => s":$s"
            case None => ""
          }
          s"${ns}/${svc}${labelStr}" -> Map(
            "state" -> act.stateSnapshot(),
            "watch" -> watchState
          )
      }
      val state = Map(
        "endpoints" -> endpointsState,
        "portMappings" -> servicesState
      )
      val json = mapper.writeValueAsString(state)

      val res = Response()
      res.mediaType = MediaType.Json
      res.contentString = json
      Future.value(res)
    }
  }

}
