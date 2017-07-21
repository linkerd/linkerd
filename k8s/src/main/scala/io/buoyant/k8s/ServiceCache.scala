package io.buoyant.k8s

import java.net.InetSocketAddress
import com.twitter.finagle.Address
import com.twitter.util.{Updatable, Var}
import io.buoyant.k8s.Ns.ListCache
import io.buoyant.k8s.v1._
import scala.collection.mutable

class ServiceCache(namespace: String)
  extends ListCache[Service, ServiceWatch, ServiceList] {
  def get(serviceName: String, portName: String): Var[Option[Var[Address]]] = synchronized {
    // we call this unstable because every change to the Address will cause
    // the entire Var[Option[Address]] to update.
    val unstable: Var[Option[Address]] = cache.get(serviceName) match {
      case Some(ports) =>
        ports.map(_.ports.get(portName))
      case None =>
        val ports = Var(ServiceCache.CacheEntry(Map.empty, Map.empty))
        cache += serviceName -> ports
        ports.map(_.ports.get(portName))
    }

    stabilize(unstable)
  }

  def getPortMapping(serviceName: String, port: Int): Var[Option[Var[String]]] = synchronized {
    // we call this unstable because every change to the target port will cause
    // the entire Var[Option[Int]] to update.
    val unstable: Var[Option[String]] = cache.get(serviceName) match {
      case Some(ports) =>
        ports.map(_.portMap.get(port))
      case None =>
        val ports = Var(ServiceCache.CacheEntry(Map.empty, Map.empty))
        cache += serviceName -> ports
        ports.map(_.portMap.get(port))
    }

    stabilize(unstable)
  }

  private[this] var cache = Map.empty[String, VarUp[ServiceCache.CacheEntry]]

  def initialize(list: ServiceList): Unit = synchronized {
    val services = for {
      service <- list.items
      meta <- service.metadata
      name <- meta.name
    } yield name -> Var(ServiceCache.extractPorts(service))
    cache = services.toMap
  }

  def update(watch: ServiceWatch): Unit = synchronized {
    watch match {
      case ServiceAdded(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          log.info("k8s ns %s added service: %s", namespace, name)
          cache.get(name) match {
            case Some(ports) =>
              ports() = ServiceCache.extractPorts(service)
            case None =>
              cache += (name -> Var(ServiceCache.extractPorts(service)))
          }
        }
      case ServiceModified(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          cache.get(name) match {
            case Some(ports) =>
              log.info("k8s ns %s modified service: %s", namespace, name)
              ports() = ServiceCache.extractPorts(service)
            case None =>
              log.warning("k8s ns %s received modified watch for unknown service %s", namespace, name)
          }
        }
      case ServiceDeleted(service) =>
        for {
          meta <- service.metadata
          name <- meta.name
        } {
          log.debug("k8s ns %s deleted service : %s", namespace, name)
          cache.get(name) match {
            case Some(ports) =>
              ports() = ServiceCache.CacheEntry(Map.empty, Map.empty)
            case None =>
              cache += (name -> Var(ServiceCache.CacheEntry(Map.empty, Map.empty)))
          }
        }
      case ServiceError(status) =>
        log.error("k8s ns %s service port watch error %s", namespace, status)
    }
  }
}

object ServiceCache {

  case class CacheEntry(ports: Map[String, Address], portMap: Map[Int, String])

  private def extractPorts(service: Service): CacheEntry = {
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
    CacheEntry(ports.toMap, portMap.toMap)
  }
}