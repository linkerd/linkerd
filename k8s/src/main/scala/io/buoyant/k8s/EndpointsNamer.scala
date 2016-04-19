package io.buoyant.k8s

import com.twitter.finagle._
import com.twitter.finagle.tracing.Trace
import com.twitter.util._
import io.buoyant.k8s.Api.Closed
import io.buoyant.k8s.v1.{Endpoints, EndpointsList, EndpointsWatch, NsApi}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

class EndpointsNamer(idPrefix: Path, mkApi: String => NsApi) extends Namer {

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
      Activity(Ns.get(nsName).services).flatMap { services =>
        log.debug("k8s ns %s initial state: %s", nsName, services.keys.mkString(", "))
        services.get(serviceName) match {
          case None =>
            log.debug("k8s ns %s service %s missing", nsName, serviceName)
            Activity.value(NameTree.Neg)

          case Some(services) =>
            log.debug("k8s ns %s service %s found", nsName, serviceName)
            Activity(services.ports).map { ports =>
              ports.get(portName) match {
                case None =>
                  log.debug("k8s ns %s service %s port %s missing", nsName, serviceName, portName)
                  NameTree.Neg

                case Some(Port(_, addr)) =>
                  log.debug("k8s ns %s service %s port %s found + %s", nsName, serviceName, portName, residual.show)
                  NameTree.Leaf(Name.Bound(addr, idPrefix ++ id, residual))
              }
            }
        }
      }

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] object Ns {
    private[this] var caches: Map[String, NsCache] = Map.empty

    def get(name: String): NsCache = synchronized {
      caches.get(name) match {
        case Some(ns) => ns
        case None =>
          val ns = mkNs(name)
          caches += (name -> ns)
          ns
      }
    }

    private[this] def mkNs(name: String): NsCache = {
      val nsCache = new NsCache(name, Var(Activity.Pending))
      _watches = _watches + (name -> watch(name, nsCache))
      nsCache
    }

    // XXX once a namespace is watched, it is watched forever.  also
    // theres probably some poor error behavior.
    private[this] var _watches = Map.empty[String, Closable]
    def watches = _watches

    private[this] def watch(namespace: String, services: NsCache): Closable = {
      val ns = mkApi(namespace)
      val endpointsApi = ns.endpoints
      val close = new AtomicReference(Closable.nop)

      Trace.letClear {
        val init = {
          log.debug("k8s initializing %s", namespace)
          val endpoints = endpointsApi.get()

          close.set(Closable.make { _ =>
            endpoints.raise(Closed)
            Future.Unit
          })

          endpoints.onSuccess { list =>
            services.initialize(list)
          }.onFailure { e =>
            log.error(e, "k8s failed to list endpoints")
          }
        }

        init.foreach { init =>
          val (updates, closable) = endpointsApi.watch(
            resourceVersion = init.metadata.flatMap(_.resourceVersion)
          )
          close.set(closable)
          updates.foreach(services.update)
        }
      }

      Closable.all(ns, Closable.ref(close))
    }
  }
}

private object EndpointsNamer {
  val PrefixLen = 3

  type VarUp[T] = Var[T] with Updatable[T]
  type ActUp[T] = VarUp[Activity.State[T]]

  case class Port(name: String, addr: VarUp[Addr])
    extends Updatable[Addr] {

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
      case (name, addrs) => name -> Port(name, Var(Addr.Bound(addrs)))
    }

  case class SvcCache(name: String, ports: ActUp[Map[String, Port]]) {

    def clear(): Unit = synchronized {
      ports.sample() match {
        case Activity.Ok(snap) =>
          for (port <- snap.values) {
            port() = Addr.Neg
          }
          ports() = Activity.Pending

        case _ =>
      }
    }

    def delete(name: String): Unit = synchronized {
      ports.sample() match {
        case Activity.Ok(snap) =>
          for (port <- snap.get(name)) {
            port() = Addr.Neg
            ports() = Activity.Ok(snap - name)
          }

        case _ =>
      }
    }

    def update(subsets: Seq[v1.EndpointSubset]): Unit =
      getAddrs(subsets) match {
        case addrs if addrs.isEmpty =>
          synchronized {
            ports.sample() match {
              case Activity.Ok(ps) =>
                for (port <- ps.values) {
                  port() = Addr.Neg
                }

              case _ =>
            }
          }

        case addrs =>
          synchronized {
            val base = ports.sample() match {
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
                    val port = Port(name, Var(addr))
                    base + (name -> port)

                  case state =>
                    log.warning("did not update port %s in state %s", name, state)
                    base
                }
            }

            if (updated.size > base.size) {
              ports() = Activity.Ok(updated)
            }
          }
      }
  }

  class NsCache(name: String, activity: ActUp[Map[String, SvcCache]]) {

    def services: Var[Activity.State[Map[String, SvcCache]]] = activity

    def clear(): Unit = synchronized {
      activity.sample() match {
        case Activity.Ok(snap) =>
          for (svc <- snap.values) {
            svc.clear()
          }
        case _ =>
      }
      activity() = Activity.Pending
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
        activity() = Activity.Ok(initSvcs.toMap)
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
        SvcCache(name, Var(Activity.Ok(ports)))
      }

    private[this] def add(endpoints: v1.Endpoints): Unit =
      for (svc <- mkSvc(endpoints)) synchronized {
        log.debug("k8s added: %s", svc.name)
        val svcs = services.sample() match {
          case Activity.Ok(svcs) => svcs
          case _ => Map.empty[String, SvcCache]
        }
        activity() = Activity.Ok(svcs + (svc.name -> svc))
      }

    private[this] def modify(endpoints: v1.Endpoints): Unit =
      for (name <- getName(endpoints)) synchronized {
        log.debug("k8s modified: %s", name)
        services.sample() match {
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
        services.sample() match {
          case Activity.Ok(snap) =>
            for (svc <- snap.get(name)) {
              svc.clear()
              activity() = Activity.Ok(snap - name)
            }

          case _ =>
        }
      }

  }

}
