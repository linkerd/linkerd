package io.buoyant.k8s

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util._
import java.util.concurrent.atomic.AtomicReference
import io.buoyant.namer.RichActivity

case class IngressSpec(
  name: Option[String],
  namespace: Option[String],
  fallbackBackend: Option[IngressPath] = None,
  rules: Seq[IngressPath]
) {
  def getMatchingPath(hostHeader: Option[String], requestPath: String): Option[IngressPath] = {
    val matchingPath = rules.find(_.matches(hostHeader, requestPath))
    (matchingPath, fallbackBackend) match {
      case (Some(path), _) =>
        log.info("k8s found rule matching %s %s: %s", hostHeader.getOrElse(""), requestPath, path)
        Some(path)
      case (None, Some(default)) =>
        log.info("k8s using default service %s for request %s %s", default, hostHeader.getOrElse(""), requestPath)
        Some(default)
      case _ =>
        log.info("k8s no suitable rule found in %s for request %s %s", name.getOrElse(""), hostHeader.getOrElse(""), requestPath)
        None
    }
  }
}

case class IngressPath(
  host: Option[String] = None,
  path: Option[String] = None,
  namespace: String,
  svc: String,
  port: String
) {
  val compiledPath = path.map(_.r)
  def uriMatches(uri: String, p: String): Boolean = p.isEmpty || (compiledPath exists { cp =>
    uri match {
      case cp() => true
      case _ => false
    }
  })

  def matches(hostHeader: Option[String], requestPath: String) = {
    (host, path) match {
      case (Some(host), Some(p)) =>
        uriMatches(requestPath, p) && hostHeader.contains(host)
      case (Some(host), None) => hostHeader.contains(host)
      case (None, Some(p)) => uriMatches(requestPath, p)
      case (None, None) => true
    }
  }

}

object IngressCache {
  type IngressState = Activity.State[Seq[IngressSpec]]
  val annotationKey = "kubernetes.io/ingress.class"

  private[k8s] def getMatchingPath(hostHeader: Option[String], requestPath: String, ingresses: Seq[IngressSpec]): Option[IngressPath] =
    ingresses
      .toIterator // stop after we find a match
      .flatMap(_.getMatchingPath(hostHeader, requestPath))
      .take(1)
      .toSeq.headOption

}

/**
 * IngressCache watches for ingress changes
 * and checks incoming requests against cached ingress rules.
 *
 * @param namespace: The k8s namespace to filter on. If None, it watches all namespaces.
 */

class IngressCache(namespace: Option[String], apiClient: Service[Request, Response], annotationClass: String) {
  import IngressCache._

  val api = namespace match {
    case Some(ns) => v1beta1.Api(apiClient).withNamespace(ns).ingresses
    case None => v1beta1.Api(apiClient).ingresses
  }

  private[this] object Closed extends Throwable
  private[this] val state = Var.async[IngressState](Activity.Pending) { state =>
    val closeRef = new AtomicReference[Closable](Closable.nop)
    val pending = api.get(retryIndefinitely = true).respond {
      case Throw(e) => state() = Activity.Failed(e)
      case Return(ingressList) =>
        val initState: Seq[IngressSpec] = ingressList.items.flatMap(mkIngress)
        state.update(Activity.Ok(initState))
        val (stream, close) = api.watch(None, None, ingressList.metadata.flatMap(_.resourceVersion))
        closeRef.set(close)
        val _ = stream.foldLeft(initState) { (ingresses, watchEvent) =>
          val newState: Seq[IngressSpec] = watchEvent match {
            case v1beta1.IngressAdded(a) => ingresses ++ mkIngress(a)
            case v1beta1.IngressModified(m) => mkIngress(m).map(item => ingresses.filterNot(isNameEqual(_, item)) :+ item).getOrElse(ingresses)
            case v1beta1.IngressDeleted(d) => mkIngress(d).map(item => ingresses.filterNot(isNameEqual(_, item))).getOrElse(ingresses)
            case v1beta1.IngressError(e) =>
              log.error("k8s watch error: %s", e)
              ingresses
          }
          state() = Activity.Ok(newState)
          newState
        }
    }
    Closable.make { t =>
      pending.raise(Closed)
      Closable.ref(closeRef).close(t)
    }
  }

  private[this] lazy val ingresses: Activity[Seq[IngressSpec]] = {
    val act = Activity(state)
    val _ = act.states.respond(_ => ()) // register a listener forever to keep the Activity open
    act
  }
  private[this] def isNameEqual(x: IngressSpec, y: IngressSpec): Boolean = x.name == y.name && x.namespace == y.namespace
  private[this] def mkIngress(ingress: v1beta1.Ingress): Option[IngressSpec] = {
    //make sure that this ingress resource is not specified for someone else
    val annotations = ingress.metadata.flatMap(meta => meta.annotations).getOrElse(Map.empty)
    annotations.get(annotationKey) match {
      case Some(ingressClass) if ingressClass != annotationClass => return None
      case _ =>
    }

    val namespace = ingress.metadata.flatMap(meta => meta.namespace)
    ingress.spec.map { spec =>
      val paths = for (
        spec <- ingress.spec.toSeq;
        rules <- spec.rules.toSeq;
        rule <- rules;
        http <- rule.http.toSeq;
        path <- http.paths
      ) yield {
        IngressPath(rule.host, path.path, namespace.getOrElse("default"), path.backend.serviceName, path.backend.servicePort)
      }

      val fallback = spec.backend.map(b => IngressPath(None, None, namespace.getOrElse("default"), b.serviceName, b.servicePort))
      IngressSpec(ingress.metadata.flatMap(_.name), namespace, fallback, paths)
    }
  }

  def matchPath(hostHeader: Option[String], requestPath: String): Future[Option[IngressPath]] =
    ingresses.map { cache: Seq[IngressSpec] =>
      getMatchingPath(hostHeader, requestPath, cache)
    }.toFuture
}
