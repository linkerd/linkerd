package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Headers, Request}
import com.twitter.finagle.param.Label
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Stack, Dtab, Path}
import com.twitter.util.{Timer, Duration, Future}
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, Ns, v1beta1}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest, _}

object IngressIdentifier {

  case class Header(key: String)
  implicit object Header extends Stack.Param[Header] {
    val default = Header(Headers.Authority)
  }

  def mk(params: Stack.Params, mkApi: String => v1beta1.Api): Identifier[Request] = {
    val DstPrefix(pfx) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
    new IngressIdentifier(pfx, baseDtab, mkApi)
  }
}

class IngressIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  mkApi: String => v1beta1.Api,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer.twitter)
  extends Identifier[Request] {

  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"no ingress rule matches")

  private[this] val ingressWithCache =
    new Ns[v1beta1.Ingress, v1beta1.IngressWatch, v1beta1.IngressList, IngressCache](backoff, timer) {
      override protected def mkResource(name: String) = mkApi(name).ingresses
      override protected def mkCache(name: String) = new IngressCache(name)
    }

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val hostHeader = req.headers.get("Host").lastOption
    val requestPath = req.path

    val cache = ingressWithCache.get("", None).items.sample()
    val matchingPaths = cache.values.flatMap { ingressResource =>
      val resourceSample = ingressResource.sample()
      val matchingPath = resourceSample.rules.find { rule =>
        (rule.host, rule.path) match {
          case (Some(host), Some(path)) => requestPath.startsWith(path) && hostHeader.map(h => h == host).getOrElse(false)
          case (Some(host), None) => hostHeader.map(h => h == host).getOrElse(false)
          case (None, Some(path)) => requestPath.startsWith(path)
          case (None, None) => false
        }
      }
      (matchingPath, resourceSample.fallbackBackend) match {
        case (Some(path), _) => Some(path)
        case (None, Some(default)) => Some(default)
        case _ => None
      }
    }

    matchingPaths.headOption match {
      case None => Future.value(unidentified)
      case Some(a) =>
        val path = pfx ++ Path.Utf8(a.namespace, a.port, a.svc)
        val dst = Dst.Path(path, baseDtab(), Dtab.local)
        Future.value(new IdentifiedRequest(dst, req))
    }
  }
}

case class NamespacedName(
  namespace: Option[String],
  name: Option[String]
)

case class IngressHTTP(
  fallbackBackend: Option[IngressPaths] = None,
  rules: Seq[IngressPaths]
)

case class IngressPaths(
  host: Option[String] = None,
  path: Option[String] = None,
  namespace: String,
  svc: String,
  port: String
)

class IngressCache(namespace: String) extends Ns.NsListCache[v1beta1.Ingress, v1beta1.IngressWatch, v1beta1.IngressList, IngressHTTP, NamespacedName](namespace) {
  def update(watch: v1beta1.IngressWatch): Unit = watch match {
    case v1beta1.IngressError(e) => println("k8s watch error: %s", e) //TODO
    case v1beta1.IngressAdded(ingress) => add(ingress)
    case v1beta1.IngressModified(ingress) => modify(ingress)
    case v1beta1.IngressDeleted(ingress) => delete(ingress)
  }

  def getName(ingress: v1beta1.Ingress): Option[NamespacedName] =
    ingress.metadata.flatMap { i =>
      (i.namespace, i.name) match {
        case (Some(ns), Some(n)) => Some(NamespacedName(i.namespace, i.name))
        case _ => None
      }
    }

  def updateItem(ingress: v1beta1.Ingress): Option[IngressHTTP] = mkItem(ingress)

  def mkItem(ingress: v1beta1.Ingress): Option[IngressHTTP] = {
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
          ingress.metadata.get.namespace.get
          IngressPaths(rule.host, path.path, namespace, path.backend.serviceName, path.backend.servicePort)
        }

        val fallback = spec.backend.map(b => IngressPaths(None, None, namespace, b.serviceName, b.servicePort))
        Some(IngressHTTP(fallback, paths))
      case None => None
    }
  }
}

case class IngressIdentifierConfig(
  k8sHost: Option[String],
  k8sPort: Option[Port]
) extends H2IdentifierConfig with ClientConfig {
  @JsonIgnore
  override def host: Option[String] = k8sHost

  @JsonIgnore
  override def portNum: Option[Int] = k8sPort.map(_.port)

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) =
    IngressIdentifier.mk(params, mkApi)

  val client = mkClient(Params.empty).configured(Label("ingress-identifier"))
  def mkApi(ns: String) = v1beta1.Api(client.newService(dst))

}

object IngressIdentifierConfig {
  val kind = "io.l5d.ingress"
}

class IngressIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IngressIdentifierConfig]
  override val configId = IngressIdentifierConfig.kind
}

object IngressIdentifierInitializer extends IngressIdentifierInitializer
