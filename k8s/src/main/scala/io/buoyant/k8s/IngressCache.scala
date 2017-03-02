package io.buoyant.k8s

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Path, Service}
import com.twitter.util.{Updatable, Activity, Var, Future}
import io.buoyant.k8s

/**
 * Ingress resource names can be reused across namespaces.
 * This tuple is to guarantee uniqueness
 */
case class NamespacedName(
  namespace: Option[String],
  name: Option[String]
)

case class IngressSpec(
  name: Option[String],
  fallbackBackend: Option[IngressPath] = None,
  rules: Seq[IngressPath]
)

case class IngressPath(
  host: Option[String] = None,
  path: Option[Path] = None,
  namespace: String,
  svc: String,
  port: String
)

object IngressCache {

  type VarUp[T] = Var[T] with Updatable[T]

  def mk(ns: Option[String], apiClient: Service[Request, Response]) =
    new Ns[v1beta1.Ingress, v1beta1.IngressWatch, v1beta1.IngressList, IngressCache]() {
      override protected def mkResource(name: Option[String]) = ns match {
        case Some(ns) => v1beta1.Api(apiClient).withNamespace(ns).ingresses
        case None => v1beta1.Api(apiClient).ingresses
      }
      override protected def mkCache(name: Option[String]) = new IngressCache(name)
    }

}

/**
 * IngressCache watches for ingress changes
 * and checks incoming requests against cached ingress rules.
 *
 * @param namespace: The k8s namespace to filter on. If None, it watches all namespaces.
 */

class IngressCache(namespace: Option[String])
  extends Ns.ObjectCache[v1beta1.Ingress, v1beta1.IngressWatch, v1beta1.IngressList]() {
  import k8s.IngressCache._

  val state = Var[Activity.State[Map[NamespacedName, VarUp[IngressSpec]]]](Activity.Pending)
  val ingresses: Activity[Map[NamespacedName, VarUp[IngressSpec]]] = Activity(state)

  /**
   * Boilerplate methods for streaming updates
   */

  def initialize(items: v1beta1.IngressList): Unit = {
    val initItems = items.items.flatMap { item =>
      getName(item).flatMap { name =>
        mkItem(item).map { i => name -> Var(i) }
      }
    }

    synchronized {
      state() = Activity.Ok(initItems.toMap)
    }
  }

  def add(obj: v1beta1.Ingress): Unit =
    for (item <- mkItem(obj)) synchronized {
      val name = getName(obj).get //todo
      log.debug("k8s ns %s added: %s", namespace.getOrElse(""), name)
      val items = state.sample() match {
        case Activity.Ok(items) => items
        case _ => Map.empty[NamespacedName, VarUp[IngressSpec]]
      }
      state() = Activity.Ok(items + (name -> Var(item)))
    }

  def modify(obj: v1beta1.Ingress): Unit =
    for (name <- getName(obj)) synchronized {
      log.debug("k8s ns %s modified: %s", namespace.getOrElse(""), name)
      state.sample() match {
        case Activity.Ok(snap) =>
          snap.get(name) match {
            case None =>
              log.warning("k8s ns %s received modified watch for unknown resource %s", namespace.getOrElse(""), name)
            case Some(item) =>
              mkItem(obj) match {
                case Some(i) => item() = i
                case None => state() = Activity.Ok(snap - name)
              }
          }
        case _ =>
      }
    }

  def delete(obj: v1beta1.Ingress): Unit =
    for (name <- getName(obj)) synchronized {
      log.debug("k8s ns %s deleted: %s", namespace.getOrElse(""), name)
      state.sample() match {
        case Activity.Ok(snap) =>
          for (svc <- snap.get(name)) {
            state() = Activity.Ok(snap - name)
          }

        case _ =>
      }
    }

  def update(watch: v1beta1.IngressWatch): Unit = watch match {
    case v1beta1.IngressError(e) => log.error("k8s watch error: %s", e)
    case v1beta1.IngressAdded(ingress) => add(ingress)
    case v1beta1.IngressModified(ingress) => modify(ingress)
    case v1beta1.IngressDeleted(ingress) => delete(ingress)
  }

  /**
   * Ingress-specific methods
   */

  def getName(ingress: v1beta1.Ingress): Option[NamespacedName] =
    ingress.metadata.flatMap { i =>
      (i.namespace, i.name) match {
        case (Some(ns), Some(n)) => Some(NamespacedName(i.namespace, i.name))
        case _ => None
      }
    }

  def mkItem(ingress: v1beta1.Ingress): Option[IngressSpec] = {
    val namespace = ingress.metadata.flatMap(meta => meta.namespace).getOrElse("default")
    ingress.spec match {
      case Some(spec) =>
        val paths = for (
          spec <- ingress.spec.toSeq;
          rules <- spec.rules.toSeq;
          rule <- rules;
          http <- rule.http.toSeq;
          path <- http.paths
        ) yield {
          IngressPath(rule.host, path.path.map(Path.read(_)), namespace, path.backend.serviceName, path.backend.servicePort)
        }

        val fallback = spec.backend.map(b => IngressPath(None, None, namespace, b.serviceName, b.servicePort))
        Some(IngressSpec(ingress.metadata.flatMap(meta => meta.name), fallback, paths))
      case None => None
    }
  }

  def isPrefix(pfx: Path, str: Path): Boolean = pfx.showElems == str.take(pfx.size).showElems
  def matchesNs(ns: String): Boolean = namespace.map(_ == ns).getOrElse(true)

  def getMatchingPath(hostHeader: Option[String], requestPath: String): Future[Option[IngressPath]] = {
    val pathToMatch = Path.read(requestPath)
    ingresses.map { cache =>
      cache.values.flatMap { ingressResource =>
        val resourceSample = ingressResource.sample()
        val matchingPath = resourceSample.rules.find { rule =>
          (rule.host, rule.path, rule.namespace) match {
            case (Some(host), Some(path), ns) =>
              isPrefix(path, pathToMatch) && hostHeader.contains(host) && matchesNs(ns)
            case (Some(host), None, ns) => hostHeader.contains(host) && matchesNs(ns)
            case (None, Some(path), ns) => isPrefix(path, pathToMatch) && matchesNs(ns)
            case (None, None, ns) => false
          }
        }
        (matchingPath, resourceSample.fallbackBackend) match {
          case (Some(path), _) =>
            log.info("k8s found rule matching %s %s: %s", hostHeader.getOrElse(""), requestPath, path)
            Some(path)
          case (None, Some(default)) if namespace.map(_ == default.namespace).getOrElse(true) =>
            log.info("k8s using default service %s for request %s %s", default, hostHeader.getOrElse(""), requestPath)
            Some(default)
          case _ =>
            log.info("k8s no suitable rule found in %s for request %s %s", resourceSample.name.getOrElse(""), hostHeader.getOrElse(""), requestPath)
            None
        }
      }.headOption
    }.values.toFuture.flatMap(Future.const)
  }
}
