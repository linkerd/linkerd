package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.v1._
import io.buoyant.namer.{EnumeratingNamer, Metadata}
import java.net.{InetAddress, InetSocketAddress}
import scala.collection.mutable

class MultiNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends EndpointsNamer(idPrefix, mkApi, backoff)(timer) {

  val PrefixLen = 3
  private[this] val variablePrefixLength = PrefixLen + labelName.size

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
        val cache = endpointNs.get(nsName, None)
        val serviceCache = serviceNs.get(nsName, None)
        lookupServices(nsName, portName, serviceName, cache, serviceCache, id, residual)

      case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
        val labelSelector = Some(s"$label=$labelValue")
        val cache = endpointNs.get(nsName, labelSelector)
        val serviceCache = serviceNs.get(nsName, labelSelector)
        lookupServices(nsName, portName, serviceName, cache, serviceCache, id, residual)

      case (id@Path.Utf8(nsName, portName, serviceName), Some(label)) =>
        log.debug("k8s lookup: ns %s service %s label value segment missing for label %s", nsName, serviceName, portName, label)
        Activity.value(NameTree.Neg)

      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}

class SingleNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  nsName: String,
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends EndpointsNamer(idPrefix, mkApi, backoff)(timer) {

  val PrefixLen = 2
  private[this] val variablePrefixLength = PrefixLen + labelName.size

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
        val cache = endpointNs.get(nsName, None)
        val serviceCache = serviceNs.get(nsName, None)
        lookupServices(nsName, portName, serviceName, cache, serviceCache, id, residual)

      case (id@Path.Utf8(portName, serviceName, labelValue), Some(label)) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
        val labelSelector = Some(s"$label=$labelValue")
        val cache = endpointNs.get(nsName, labelSelector)
        val serviceCache = serviceNs.get(nsName, labelSelector)
        lookupServices(nsName, portName, serviceName, cache, serviceCache, id, residual)

      case (id@Path.Utf8(portName, serviceName), Some(label)) =>
        log.debug("k8s lookup: ns %s service %s label value segment missing for label %s", nsName, serviceName, portName, label)
        Activity.value(NameTree.Neg)

      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}

abstract class EndpointsNamer(
  idPrefix: Path,
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends EnumeratingNamer {

  import EndpointsNamer._

  def lookup(path: Path): Activity[NameTree[Name]]

  private[k8s] def lookupServices(
    nsName: String,
    portName: String,
    serviceName: String,
    cache: NsCache,
    serviceCache: ServiceCache,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = cache.services.flatMap { services =>
    log.debug("k8s ns %s initial state: %s", nsName, services.keys.mkString(", "))
    services.get(serviceName) match {
      case None =>
        log.debug("k8s ns %s service %s missing", nsName, serviceName)
        Activity.value(NameTree.Neg)

      case Some(service) =>
        log.debug("k8s ns %s service %s found", nsName, serviceName)
        Try(portName.toInt).toOption match {
          case Some(portNumber) =>
            val state: Var[Activity.State[NameTree[Name.Bound]]] = serviceCache.getPortMapping(serviceName, portNumber).map {
              case Some(targetPort) =>
                val addr = targetPort.flatMap(service.port)
                log.debug("k8s ns %s service %s port :%d found + %s", nsName, serviceName, portNumber, residual.show)
                Activity.Ok(NameTree.Leaf(Name.Bound(addr, idPrefix ++ id, residual)))
              case None =>
                log.debug("k8s ns %s service %s port :%d missing", nsName, serviceName, portNumber)
                Activity.Ok(NameTree.Neg)
            }
            Activity(state)
          case None =>
            val state: Var[Activity.State[NameTree[Name.Bound]]] = service.port(portName).map {
              case Some(addr) =>
                log.debug("k8s ns %s service %s port %s found + %s", nsName, serviceName, portName, residual.show)
                Activity.Ok(NameTree.Leaf(Name.Bound(addr, idPrefix ++ id, residual)))
              case None =>
                log.debug("k8s ns %s service %s port %s missing", nsName, serviceName, portName)
                Activity.Ok(NameTree.Neg)
            }
            Activity(state)
        }
    }
  }

  private[k8s] val endpointNs =
    new Ns[Endpoints, EndpointsWatch, EndpointsList, NsCache](backoff, timer) {
      override protected def mkResource(name: String) = mkApi(name).endpoints
      override protected def mkCache(name: String) = new NsCache(name)
    }

  private[k8s] val serviceNs = new Ns[Service, ServiceWatch, ServiceList, ServiceCache](backoff, timer) {
    override protected def mkResource(name: String) = mkApi(name).services
    override protected def mkCache(name: String) = new ServiceCache(name)
  }

  override val getAllNames: Activity[Set[Path]] = {
    // explicit type annotations are required for scala to pick the right
    // versions of flatMap and map
    val namespaces: ActSet[String] = Activity(endpointNs.namespaces.map(Activity.Ok(_)))
    namespaces.flatMap { namespace: String =>
      val services: ActSet[SvcCache] = endpointNs.get(namespace, None).services.map(_.values.toSet)
      services.flatMap { service: SvcCache =>
        val ports: Var[Set[String]] = service.ports.map(_.keys.toSet)
        val states = ports.map { ports =>
          val paths = ports.map { port =>
            idPrefix ++ Path.Utf8(namespace, port, service.name)
          }
          Activity.Ok(paths)
        }
        Activity(states)
      }
    }
  }
}

private object EndpointsNamer {
  case class Endpoint(ip: InetAddress, nodeName: Option[String])

  case class Svc(endpoints: Set[Endpoint], ports: Map[String, Int])

  private[this] def getEndpoints(subsets: Option[Seq[v1.EndpointSubset]]): Set[Endpoint] = {
    val endpoints = mutable.Set.empty[Endpoint]
    for {
      subset <- subsets.getOrElse(Seq.empty)
      addresses <- subset.addresses
      addrs <- addresses
    } {
      endpoints += Endpoint(InetAddress.getByName(addrs.ip), addrs.nodeName)
    }
    endpoints.toSet
  }

  private[this] def getPorts(subsets: Option[Seq[v1.EndpointSubset]]): Map[String, Int] = {
    val portSet = mutable.Map.empty[String, Int]
    for {
      subset <- subsets.getOrElse(Seq.empty)
      ports <- subset.ports
      port <- ports
      name <- port.name
    } {
      val proto = port.protocol.map(_.toUpperCase).getOrElse("TCP")
      if (proto == "TCP") {
        portSet(name) = port.port
      }
    }
    portSet.toMap
  }

  case class SvcCache(name: String, init: Svc) {

    private[this] val endpointsState = Var[Set[Endpoint]](init.endpoints)
    private[this] val portsState = Var[Map[String, Int]](init.ports)

    def port(portName: String): Var[Option[Var[Addr]]] = {
      portsState.map { portMap =>
        val portNumber = portMap.get(portName)
        portNumber.map(port)
      }
    }

    def port(portNumber: Int): Var[Addr] =
      endpointsState.map { endpoints =>
        val addrs: Set[Address] = endpoints.map { endpoint =>
          val isa = new InetSocketAddress(endpoint.ip, portNumber)
          Address.Inet(isa, endpoint.nodeName.map(Metadata.nodeName -> _).toMap)
        }
        Addr.Bound(addrs)
      }

    def ports: Var[Map[String, Int]] = portsState

    def update(subsets: Option[Seq[v1.EndpointSubset]]): Unit = {
      val newEndpoints = getEndpoints(subsets)
      val newPorts = getPorts(subsets)

      synchronized {
        val oldEndpoints = endpointsState.sample()
        if (newEndpoints != oldEndpoints) endpointsState() = newEndpoints
        val oldPorts = portsState.sample()
        if (newPorts != oldPorts) portsState() = newPorts
      }
    }
  }

  class NsCache(namespace: String) extends Ns.ObjectCache[Endpoints, EndpointsWatch, EndpointsList] {

    private[this] val state = Var[Activity.State[Map[String, SvcCache]]](Activity.Pending)

    val services: Activity[Map[String, SvcCache]] = Activity(state)

    /**
     * Initialize a namespaces of services.  The activity is updated
     * once with the entire state of the namespace (i.e. not
     * incrementally service by service).
     */
    def initialize(endpoints: v1.EndpointsList): Unit = {
      val initSvcs = endpoints.items.flatMap { endpoint =>
        mkSvc(endpoint).map { svc => svc.name -> svc }
      }

      synchronized {
        state() = Activity.Ok(initSvcs.toMap)
      }
    }

    def update(watch: EndpointsWatch): Unit = watch match {
      case EndpointsError(e) => log.error("k8s watch error: %s", e)
      case EndpointsAdded(endpoints) => add(endpoints)
      case EndpointsModified(endpoints) => modify(endpoints)
      case EndpointsDeleted(endpoints) => delete(endpoints)
    }

    private[this] def getName(endpoints: v1.Endpoints) =
      endpoints.metadata.flatMap(_.name)

    private[this] def mkSvc(endpoints: v1.Endpoints): Option[SvcCache] =
      getName(endpoints).map { name =>
        SvcCache(name, Svc(
          getEndpoints(endpoints.subsets),
          getPorts(endpoints.subsets)
        ))
      }

    private[this] def add(endpoints: v1.Endpoints): Unit =
      for (svc <- mkSvc(endpoints)) synchronized {
        log.debug("k8s ns %s added: %s", namespace, svc.name)
        val svcs = state.sample() match {
          case Activity.Ok(svcs) => svcs
          case _ => Map.empty[String, SvcCache]
        }
        state() = Activity.Ok(svcs + (svc.name -> svc))
      }

    private[this] def modify(endpoints: v1.Endpoints): Unit =
      for (name <- getName(endpoints)) synchronized {
        log.debug("k8s ns %s modified: %s", namespace, name)
        state.sample() match {
          case Activity.Ok(snap) =>
            snap.get(name) match {
              case None =>
                log.warning("k8s ns %s received modified watch for unknown service %s", namespace, name)
              case Some(svc) =>
                svc() = endpoints.subsets
            }
          case _ =>
        }
      }

    private[this] def delete(endpoints: v1.Endpoints): Unit =
      for (name <- getName(endpoints)) synchronized {
        log.debug("k8s ns %s deleted: %s", namespace, name)
        state.sample() match {
          case Activity.Ok(snap) =>
            for (svc <- snap.get(name)) {
              state() = Activity.Ok(snap - name)
            }

          case _ =>
        }
      }
  }

  private implicit class ActSet[A](val actSet: Activity[Set[A]]) extends AnyVal {
    def map[B](f: A => B): Activity[Set[B]] = actSet.map(_.map(f))

    def flatMap[B](f: A => Activity[Set[B]]): Activity[Set[B]] =
      actSet.flatMap { as =>
        Activity.collect(as.map(f)).map(_.flatten)
      }
  }
}
