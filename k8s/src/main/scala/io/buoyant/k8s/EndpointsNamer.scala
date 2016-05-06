package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.v1.{EndpointsWatch, NsApi}
import scala.collection.mutable

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
      Ns.get(nsName).services.flatMap { services =>
        log.debug("k8s ns %s initial state: %s", nsName, services.keys.mkString(", "))
        services.get(serviceName) match {
          case None =>
            log.debug("k8s ns %s service %s missing", nsName, serviceName)
            Activity.value(NameTree.Neg)

          case Some(services) =>
            log.debug("k8s ns %s service %s found", nsName, serviceName)
            services.ports.map { ports =>
              ports.get(portName) match {
                case None =>
                  log.debug("k8s ns %s service %s port %s missing", nsName, serviceName, portName)
                  NameTree.Neg

                case Some(port) =>
                  log.debug("k8s ns %s service %s port %s found + %s", nsName, serviceName, portName, residual.show)
                  NameTree.Leaf(Name.Bound(port.addr, idPrefix ++ id, residual))
              }
            }
        }
      }

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] object Ns {
    private[this] var caches: Map[String, NsCache] = Map.empty
    // XXX once a namespace is watched, it is watched forever.
    private[this] var _watches = Map.empty[String, Activity[Closable]]
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
              val _ = Future.sleep(delay).onSuccess { _ => _retryToActivity(rest, state)(go) }
            case Stream.Empty =>
          }
      }
    }

    def get(name: String): NsCache = synchronized {
      caches.get(name) match {
        case Some(ns) => ns
        case None =>
          val ns = new NsCache(name)
          val closable = retryToActivity { watch(name, ns) }
          _watches += (name -> closable)
          caches += (name -> ns)
          ns
      }
    }

    private[this] def watch(namespace: String, services: NsCache): Future[Closable] = {
      val ns = mkApi(namespace)
      val endpointsApi = ns.endpoints

      Trace.letClear {
        log.debug("k8s initializing %s", namespace)
        endpointsApi.get().map { list =>
          services.initialize(list)
          val (updates, closable) = endpointsApi.watch(
            resourceVersion = list.metadata.flatMap(_.resourceVersion)
          )
          // fire-and-forget this traversal over an AsyncStream that updates the services state
          val _ = updates.foreach(services.update)
          closable
        }.onFailure { e =>
          log.error(e, "k8s failed to list endpoints")
        }
      }
    }
  }
}

private object EndpointsNamer {
  val PrefixLen = 3

  case class Port(name: String, init: Addr) {

    val addr = Var(init)

    def update(a: Addr) = addr.update(a)
    def sample() = addr.sample()
  }

  private[this] def getAddrs(subsets: Seq[v1.EndpointSubset]): Map[String, Set[Address]] = {
    val addrsByPort = mutable.Map.empty[String, Set[Address]]

    for (subset <- subsets) {
      val ips = subset.addresses match {
        case None => Set.empty
        case Some(addrs) => addrs.map(_.ip).toSet
      }

      for {
        ports <- subset.ports
        port <- ports
      } {
        val proto = port.protocol.map(_.toUpperCase).getOrElse("TCP")
        (proto, port.name) match {
          case ("TCP", Some(name)) =>
            val addrs: Set[Address] = ips.map(ip => Address(ip, port.port))
            addrsByPort(name) = addrsByPort.getOrElse(name, Set.empty) ++ addrs

          case _ =>
        }
      }
    }

    addrsByPort.toMap
  }

  private[this] def mkPorts(subsets: Seq[v1.EndpointSubset]): Map[String, Port] =
    getAddrs(subsets).map {
      case (name, addrs) => name -> Port(name, Addr.Bound(addrs))
    }

  case class SvcCache(name: String, init: Map[String, Port]) {

    private[this] val state = Var[Activity.State[Map[String, Port]]](Activity.Ok(init))

    val ports = Activity(state)

    def clear(): Unit = synchronized {
      state.sample() match {
        case Activity.Ok(snap) =>
          for (port <- snap.values) {
            port() = Addr.Neg
          }
          state() = Activity.Pending

        case _ =>
      }
    }

    def delete(name: String): Unit = synchronized {
      state.sample() match {
        case Activity.Ok(snap) =>
          for (port <- snap.get(name)) {
            port() = Addr.Neg
            state() = Activity.Ok(snap - name)
          }

        case _ =>
      }
    }

    def update(subsets: Seq[v1.EndpointSubset]): Unit =
      getAddrs(subsets) match {
        case addrs if addrs.isEmpty =>
          synchronized {
            state.sample() match {
              case Activity.Ok(ps) =>
                for (port <- ps.values) {
                  port() = Addr.Neg
                }

              case _ =>
            }
          }

        case addrs =>
          synchronized {
            val base = state.sample() match {
              case Activity.Ok(base) => base
              case _ => Map.empty[String, Port]
            }

            val updated = addrs.foldLeft(base) {
              case (base, (name, addrs)) =>
                val addr = if (addrs.isEmpty) Addr.Neg else Addr.Bound(addrs)
                base.get(name) match {
                  case Some(port) =>
                    port() = addr
                    base

                  case None =>
                    val port = Port(name, addr)
                    base + (name -> port)

                  case state =>
                    log.warning("did not update port %s in state %s", name, state)
                    base
                }
            }

            if (updated.size > base.size) {
              state() = Activity.Ok(updated)
            }
          }
      }
  }

  class NsCache(name: String) {

    private[this] val state = Var[Activity.State[Map[String, SvcCache]]](Activity.Pending)

    val services: Activity[Map[String, SvcCache]] = Activity(state)

    def clear(): Unit = synchronized {
      state.sample() match {
        case Activity.Ok(snap) =>
          for (svc <- snap.values) {
            svc.clear()
          }
        case _ =>
      }
      state() = Activity.Pending
    }

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
      case EndpointsWatch.Error(e) => log.error("k8s watch error: %s", e)
      case EndpointsWatch.Added(endpoints) => add(endpoints)
      case EndpointsWatch.Modified(endpoints) => modify(endpoints)
      case EndpointsWatch.Deleted(endpoints) => delete(endpoints)
    }

    private[this] def getName(endpoints: v1.Endpoints) =
      endpoints.metadata.flatMap(_.name)

    private[this] def mkSvc(endpoints: v1.Endpoints): Option[SvcCache] =
      getName(endpoints).map { name =>
        val ports = mkPorts(endpoints.subsets)
        SvcCache(name, ports)
      }

    private[this] def add(endpoints: v1.Endpoints): Unit =
      for (svc <- mkSvc(endpoints)) synchronized {
        log.debug("k8s added: %s", svc.name)
        val svcs = state.sample() match {
          case Activity.Ok(svcs) => svcs
          case _ => Map.empty[String, SvcCache]
        }
        state() = Activity.Ok(svcs + (svc.name -> svc))
      }

    private[this] def modify(endpoints: v1.Endpoints): Unit =
      for (name <- getName(endpoints)) synchronized {
        log.debug("k8s modified: %s", name)
        state.sample() match {
          case Activity.Ok(snap) =>
            snap.get(name) match {
              case None =>
                log.warning("received modified watch for unknown service %s", name)
              case Some(svc) =>
                svc() = endpoints.subsets
            }
          case _ =>
        }
      }

    private[this] def delete(endpoints: v1.Endpoints): Unit =
      for (name <- getName(endpoints)) synchronized {
        log.debug("k8s deleted: %s", name)
        state.sample() match {
          case Activity.Ok(snap) =>
            for (svc <- snap.get(name)) {
              svc.clear()
              state() = Activity.Ok(snap - name)
            }

          case _ =>
        }
      }
  }
}
