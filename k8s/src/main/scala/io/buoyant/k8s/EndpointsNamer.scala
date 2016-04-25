package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.v1.{EndpointsWatch, NsApi}

class EndpointsNamer(
  idPrefix: Path,
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer.twitter) extends Namer {

  import EndpointsNamer._

  /**
   * Accepts names in the form:
   *   /<namespace>/<port-name>/<svc-name>/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * kubernetes master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = path.take(PrefixLen) match {
    case id@Path.Utf8(nsName, portName, serviceName) =>
      val residual = path.drop(PrefixLen)
      log.debug("k8s lookup: %s %s", id.show, path.show)
      Ns.get(nsName).map { nsCache =>
        log.debug("k8s ns %s initial state: %s", nsName, nsCache.services.keys.mkString(", "))
        nsCache.services.get(serviceName) match {
          case None =>
            log.debug("k8s ns %s service %s missing", nsName, serviceName)
            NameTree.Neg

          case Some(services) =>
            log.debug("k8s ns %s service %s found", nsName, serviceName)
            services.ports.get(portName) match {
              case None =>
                log.debug("k8s ns %s service %s port %s missing", nsName, serviceName, portName)
                NameTree.Neg

              case Some(port) =>
                log.debug("k8s ns %s service %s port %s found + %s", nsName, serviceName, portName, residual.show)
                NameTree.Leaf(Name.Bound(Var(port.addr), idPrefix ++ id, residual))
            }
        }
      }

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] object Ns {
    // XXX once a namespace is watched, it is watched forever.
    private[this] var _watches = Map.empty[String, Activity[NsCache]]
    def watches = _watches

    /**
     * Returns an Activity backed by a Future.  The resultant Activity is pending until the
     * original future is satisfied.  When the Future is successful, the Activity becomes
     * an Activity.Ok with a fixed value from the Future.  If the Future fails, the Activity
     * becomes an Activity.Failed and the Future is retried with the given backoff schedule.
     * Therefore, the legal state transitions are:
     *
     * Pending -> Ok
     * Pending -> Failed
     * Failed -> Failed
     * Failed -> Ok
     */
    private[this] def retryToActivity[T](go: => Future[T]): Activity[T] = {
      val state = Var[Activity.State[T]](Activity.Pending)
      _retryToActivity(backoff, state)(go)
      Activity(state)
    }

    private[this] def _retryToActivity[T](
      remainingBackoff: Stream[Duration],
      state: Var[Activity.State[T]] with Updatable[Activity.State[T]] = Var[Activity.State[T]](Activity.Pending)
    )(go: => Future[T]): Unit = {
      val _ = go.respond {
        case Return(t) =>
          state() = Activity.Ok(t)
        case Throw(e) =>
          state() = Activity.Failed(e)
          remainingBackoff match {
            case delay #:: rest =>
              Future.sleep(delay).onSuccess { _ => _retryToActivity(rest, state)(go) }
            case Stream.Empty =>
          }
      }
    }

    def get(name: String): Activity[NsCache] = synchronized {
      _watches.get(name) match {
        case Some(ns) => ns
        case None =>
          val ns = watch(name)
          _watches += (name -> ns)
          ns
      }
    }

    private[this] def watch(namespace: String): Activity[NsCache] = {
      val ns = mkApi(namespace)
      val endpointsApi = ns.endpoints

      Trace.letClear {
        log.debug("k8s initializing %s", namespace)
        retryToActivity { endpointsApi.get() }.flatMap { list =>
          val init = NsCache(namespace, list)
          val (events, closable) = endpointsApi.watch(
            resourceVersion = list.metadata.flatMap(_.resourceVersion)
          )
          val nsCache = Var.async(init) { updates =>
            @volatile var current = init
            // fire-and-forget this traversal over an AsyncStream that updates the services state
            val _ = events.foreach { event =>
              current = current.update(event)
              updates.update(current)
            }
            closable
          }
          Activity(nsCache.map(Activity.Ok(_)))
        }
      }
    }
  }
}

private object EndpointsNamer {
  val PrefixLen = 3

  private[this] def getName(endpoints: v1.Endpoints): Option[String] =
    endpoints.metadata.flatMap(_.name)

  case class Port(name: String, addr: Addr)

  object Port {
    def mkPorts(subsets: Seq[v1.EndpointSubset]): Map[String, Port] = {

      val ports = for {
        subset <- subsets
        ips = subset.addresses match {
          case None => Set.empty
          case Some(addrs) => addrs.map(_.ip).toSet
        }
        ports <- subset.ports.toSeq
        port <- ports
        portName <- port.name
        proto = port.protocol.map(_.toUpperCase).getOrElse("TCP")
        if proto == "TCP"
        addrs = ips.map(ip => Address(ip, port.port))
      } yield portName -> addrs

      val portMap = ports.foldLeft(Map.empty[String, Set[Address]]) {
        case (acc, (name, addresses)) =>
          val union = acc.getOrElse(name, Set.empty) ++ addresses
          acc + (name -> union)
      }

      portMap.map {
        case (name, addresses) =>
          val addr = if (addresses.isEmpty)
            Addr.Neg
          else
            Addr.Bound(addresses)
          name -> Port(name, addr)
      }
    }
  }

  case class SvcCache(name: String, ports: Map[String, Port]) {
    def delete(name: String): SvcCache =
      copy(ports = ports - name)

    def update(subsets: Seq[v1.EndpointSubset]): SvcCache =
      copy(ports = ports ++ Port.mkPorts(subsets))
  }

  object SvcCache {
    def mkSvc(endpoints: v1.Endpoints): Option[SvcCache] = {
      getName(endpoints).map { name =>
        val ports = Port.mkPorts(endpoints.subsets)
        SvcCache(name, ports)
      }
    }
  }

  case class NsCache(name: String, services: Map[String, SvcCache] = Map.empty) {

    def update(watch: EndpointsWatch): NsCache = watch match {
      case EndpointsWatch.Error(e) =>
        log.error("k8s watch error: %s", e)
        this
      case EndpointsWatch.Added(endpoints) => add(endpoints)
      case EndpointsWatch.Modified(endpoints) => modify(endpoints)
      case EndpointsWatch.Deleted(endpoints) => delete(endpoints)
    }

    private[this] def add(endpoints: v1.Endpoints): NsCache = {
      val service = SvcCache.mkSvc(endpoints).map { svc =>
        log.debug("k8s added %s", svc.name)
        svc.name -> svc
      }.toMap
      copy(services = services ++ service)
    }

    private[this] def modify(endpoints: v1.Endpoints): NsCache =
      getName(endpoints) match {
        case Some(svcName) =>
          log.debug("k8s modified: %s", svcName)
          val updated = services.get(svcName).map { svc =>
            svcName -> svc.update(endpoints.subsets)
          }.toMap
          if (updated.isEmpty) log
            .warning("received modified watch for unknown service %s", svcName)
          copy(services = services ++ updated)
        case None =>
          log.warning("could not determine name for endpoints modified")
          this
      }

    private[this] def delete(endpoints: v1.Endpoints): NsCache =
      getName(endpoints) match {
        case Some(svcName) =>
          log.debug("k8s deleted: %s", svcName)
          copy(services = services - svcName)
        case None =>
          log.warning("could not determine name for endpoints deleted")
          this
      }
  }

  object NsCache {
    def apply(name: String, endpoints: v1.EndpointsList): NsCache = {
      val services = endpoints.items.flatMap { endpoint =>
        SvcCache.mkSvc(endpoint).map { svc => svc.name -> svc }
      }.toMap
      new NsCache(name, services)
    }
  }

}
