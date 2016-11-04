package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import io.buoyant.k8s.v1._
import io.buoyant.namer.EnumeratingNamer
import scala.collection.mutable

class EndpointsNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer.twitter) extends EnumeratingNamer {

  import EndpointsNamer._

  /**
   * Accepts names in the form:
   *   /<namespace>/<port-name>/<svc-name>/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * kubernetes master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = (path.take(variablePrefixLength), labelName) match {
    case (id@Path.Utf8(nsName, _, _), None) =>
      val residual = path.drop(variablePrefixLength)
      log.debug("k8s lookup: %s %s", id.show, path.show)
      lookupServices(endpointNs.get(nsName, None), id, residual)

    case (id@Path.Utf8(nsName, _, _, labelValue), Some(label)) =>
      val residual = path.drop(variablePrefixLength)
      log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
      lookupServices(endpointNs.get(nsName, Some(s"$label=$labelValue")), id, residual)

    case (id@Path.Utf8(nsName, portName, serviceName), Some(label)) =>
      log.debug("k8s lookup: ns %s service %s label value segment missing for label %s", nsName, serviceName, portName, label)
      Activity.value(NameTree.Neg)

    case _ =>
      Activity.value(NameTree.Neg)
  }

  private[this] def lookupServices(
    cache: NsCache,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = id.take(PrefixLen) match {
    case id@Path.Utf8(nsName, portName, serviceName) =>
      cache.services.flatMap { services =>
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
  }

  private[this] val variablePrefixLength = PrefixLen + labelName.size

  private[this] val endpointNs =
    new Ns[Endpoints, EndpointsWatch, EndpointsList, NsCache](backoff, timer) {
      override protected def mkResource(name: String) = mkApi(name).endpoints
      override protected def mkCache(name: String) = new NsCache(name)
    }

  override val getAllNames: Activity[Set[Path]] = {
    // explicit type annotations are required for scala to pick the right
    // versions of flatMap and map
    val namespaces: ActSet[String] = Activity(endpointNs.namespaces.map(Activity.Ok(_)))
    namespaces.flatMap { namespace: String =>
      val services: ActSet[SvcCache] = endpointNs.get(namespace, None).services.map(_.values.toSet)
      services.flatMap { service: SvcCache =>
        val ports: ActSet[Port] = service.ports.map(_.values.toSet)
        ports.map { port: Port =>
          idPrefix ++ Path.Utf8(namespace, port.name, service.name)
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
        val ports = mkPorts(endpoints.subsets)
        SvcCache(name, ports)
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
