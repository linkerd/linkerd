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
    matchingPath match {
      case Some(path) => log.debug("ingress %s.%s: found rule matching %s %s: %s", name.getOrElse("unknown"), namespace.getOrElse("default"), hostHeader.getOrElse(""), requestPath, path)
      case None => log.debug("ingress %s.%s: no rules found matching %s %s", name.getOrElse("unknown"), namespace.getOrElse("default"), hostHeader.getOrElse(""), requestPath)
    }
    matchingPath
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

  private[k8s] def iterateForMatch(ingresses: Seq[IngressSpec], fn: (IngressSpec) => Option[IngressPath]) =
    ingresses
      .toIterator // stop after we find a match
      .flatMap(fn(_))
      .take(1)
      .toSeq.headOption

  private[k8s] def getMatchingPath(hostHeader: Option[String], requestPath: String, ingresses: Seq[IngressSpec]): Option[IngressPath] =
    iterateForMatch(ingresses, _.getMatchingPath(hostHeader, requestPath))
      .orElse(iterateForMatch(ingresses, _.fallbackBackend))

}

/**
 * IngressCache watches for ingress changes
 * and checks incoming requests against cached ingress rules.
 *
 * @param namespace: The k8s namespace to filter on. If None, it watches all namespaces.
 * @param ignoreDefaultBackends: Don't create a 'fallback' IngressPath that matches any request for each IngressSpec.
 */

class IngressCache(
  namespace: Option[String],
  apiClient: Service[Request, Response],
  annotationClass: String,
  ignoreDefaultBackends: Boolean = false
) {
  import IngressCache._

  val api = namespace match {
    case Some(ns) => v1beta1.Api(apiClient).withNamespace(ns).ingresses
    case None => v1beta1.Api(apiClient).ingresses
  }

  private[this] lazy val ingresses: Activity[Seq[IngressSpec]] = {
    val act = api.activity(unpackIngressList) {
      (ingresses, watchEvent) =>
        watchEvent match {
          case v1beta1.IngressAdded(a) => ingresses ++ mkIngress(a)
          case v1beta1.IngressModified(m) =>
            mkIngress(m)
              .map { item => ingresses.filterNot(isNameEqual(_, item)) :+ item }
              .getOrElse(ingresses)
          case v1beta1.IngressDeleted(d) =>
            mkIngress(d)
              .map { item => ingresses.filterNot(isNameEqual(_, item)) }
              .getOrElse(ingresses)
          case v1beta1.IngressError(e) =>
            log.error("k8s watch error: %s", e)
            ingresses
        }
    }
    val _ = act.underlying.states.respond(_ => ()) // register a listener forever to keep the Activity open
    act.underlying
  }
  private[this] def unpackIngressList(response: Option[v1beta1.IngressList]): Seq[IngressSpec] = for {
    ingressList <- response.toSeq
    item <- ingressList.items
    ingress <- mkIngress(item)
  } yield ingress
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

      val name = ingress.metadata.flatMap(_.name)
      val fallback = if (ignoreDefaultBackends) {
        log.warning("ingress %s.%s: ignoreDefaultBackends enabled, ignoring default backend", name.getOrElse("unknown"), namespace.getOrElse("default"))
        None
      } else spec.backend.map(b => IngressPath(None, None, namespace.getOrElse("default"), b.serviceName, b.servicePort))
      IngressSpec(name, namespace, fallback, paths)
    }
  }

  def matchPath(hostHeader: Option[String], requestPath: String): Future[Option[IngressPath]] = {
    val hostHeaderSansPort = hostHeader.map {
      _.split(":") match {
        case Array(h: String, _) => h
        case Array(h: String) => h
        case _ => throw new IllegalArgumentException("unable to parse host for request")
      }
    }
    ingresses.map { cache: Seq[IngressSpec] =>
      getMatchingPath(hostHeaderSansPort, requestPath, cache)
    }.toFuture
  }
}
