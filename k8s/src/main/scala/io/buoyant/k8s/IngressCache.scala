package io.buoyant.k8s

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Path, Service}
import com.twitter.util._
import java.util.concurrent.atomic.AtomicReference

case class IngressSpec(
  name: Option[String],
  namespace: Option[String],
  fallbackBackend: Option[IngressPath] = None,
  rules: Seq[IngressPath]
)

case class IngressPath(
  host: Option[String] = None,
  uri: Option[String] = None,
  namespace: String,
  svc: String,
  port: String
)

object IngressCache {
  type IngressState = Activity.State[Seq[IngressSpec]]

  private[k8s] def isPrefix(pfx: String, uri: String): Boolean = {
    val pfxSegments = pfx.split("/")
    val uriSegments = uri.split("/")
    pfxSegments.corresponds(uriSegments.take(pfxSegments.size)) { _ == _ }
  }

  private[k8s] def getMatchingPath(hostHeader: Option[String], requestPath: String, ns: Option[String], ingresses: Seq[IngressSpec]): Option[IngressPath] = {
    ingresses.flatMap { ingressResource =>
      val matchingPath = ingressResource.rules.find { rule =>
        (rule.host, rule.uri, rule.namespace) match {
          case (Some(host), Some(path), ns) =>
            isPrefix(path, requestPath) && hostHeader.contains(host)
          case (Some(host), None, ns) => hostHeader.contains(host)
          case (None, Some(path), ns) => isPrefix(path, requestPath)
          case (None, None, ns) => false
        }
      }
      (matchingPath, ingressResource.fallbackBackend) match {
        case (Some(path), _) =>
          log.info("k8s found rule matching %s %s: %s", hostHeader.getOrElse(""), requestPath, path)
          Some(path)
        case (None, Some(default)) if ns.map(_ == default.namespace).getOrElse(true) =>
          log.info("k8s using default service %s for request %s %s", default, hostHeader.getOrElse(""), requestPath)
          Some(default)
        case _ =>
          log.info("k8s no suitable rule found in %s for request %s %s", ingressResource.name.getOrElse(""), hostHeader.getOrElse(""), requestPath)
          None
      }
    }.headOption
  }
}

/**
 * IngressCache watches for ingress changes
 * and checks incoming requests against cached ingress rules.
 *
 * @param namespace: The k8s namespace to filter on. If None, it watches all namespaces.
 */

class IngressCache(namespace: Option[String], apiClient: Service[Request, Response]) {
  import IngressCache._

  val api = namespace match {
    case Some(ns) => v1beta1.Api(apiClient).withNamespace(ns).ingresses
    case None => v1beta1.Api(apiClient).ingresses
  }

  private[this] object Closed extends Throwable
  private[this] val state = Var.async[IngressState](Activity.Pending) { state =>
    val closeRef = new AtomicReference[Closable](Closable.nop)
    val pending = api.get(None, None, None, retryIndefinitely = true).respond {
      case Throw(e) => state.update(Activity.Failed(e))
      case Return(ingressList) =>
        val initState: Seq[IngressSpec] = ingressList.items.flatMap(item => mkItem(item))
        state.update(Activity.Ok(initState))
        val (stream, close) = api.watch(None, None, None)
        closeRef.set(close)
        val _ = stream.foldLeft(initState) { (ingresses, watchEvent) =>
          val newState: Seq[IngressSpec] = watchEvent match {
            case v1beta1.IngressAdded(a) => mkItem(a).map(item => ingresses :+ item).getOrElse(ingresses)
            case v1beta1.IngressModified(m) => mkItem(m).map(item => ingresses.filterNot(i => isNameEqual(i, item)) :+ item).getOrElse(ingresses)
            case v1beta1.IngressDeleted(d) => mkItem(d).map(item => ingresses.filterNot(i => isNameEqual(i, item))).getOrElse(ingresses)
            case v1beta1.IngressError(e) =>
              log.error("k8s watch error: %s", e)
              ingresses
          }
          state.update(Activity.Ok(newState))
          newState
        }
    }
    Closable.make { t =>
      pending.raise(Closed)
      Closable.ref(closeRef).close(t)
    }
  }

  private[this] val ingresses: Activity[Seq[IngressSpec]] = Activity(state)
  private[this] def isNameEqual(x: IngressSpec, y: IngressSpec): Boolean = x.name == y.name && x.namespace == y.namespace
  private[this] def mkItem(ingress: v1beta1.Ingress): Option[IngressSpec] = {
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
          IngressPath(rule.host, path.path, namespace, path.backend.serviceName, path.backend.servicePort)
        }

        val fallback = spec.backend.map(b => IngressPath(None, None, namespace, b.serviceName, b.servicePort))
        Some(IngressSpec(ingress.metadata.flatMap(_.name), ingress.metadata.flatMap(_.namespace), fallback, paths))
      case None => None
    }
  }

  def matchPath(hostHeader: Option[String], requestPath: String): Future[Option[IngressPath]] =
    ingresses.map { cache: Seq[IngressSpec] =>
      getMatchingPath(hostHeader, requestPath, namespace, cache)
    }.values.toFuture.flatMap(Future.const)
}
