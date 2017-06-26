package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Dtab, Path, Service, http}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, IngressCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest, _}

class IngressIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  namespace: Option[String],
  apiClient: Service[http.Request, http.Response],
  annotationClass: String
) extends Identifier[Request] {

  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"no ingress rule matches")

  private[this] val ingressCache = new IngressCache(namespace, apiClient, annotationClass)

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val matchingPath = ingressCache.matchPath(req.host, req.path)
    matchingPath.flatMap {
      case None => Future.value(unidentified)
      case Some(a) =>
        val path = pfx ++ Path.Utf8(a.namespace, a.port, a.svc)
        val dst = Dst.Path(path, baseDtab(), Dtab.local)
        Future.value(new IdentifiedRequest(dst, req))
    }
  }
}

case class IngressIdentifierConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String]
) extends HttpIdentifierConfig with ClientConfig {
  @JsonIgnore
  override def portNum: Option[Int] = port.map(_.port)

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val client = mkClient(Params.empty).configured(Label("ingress-identifier"))
    new IngressIdentifier(prefix, baseDtab, namespace, client.newService(dst), "linkerd")
  }
}

object IngressIdentifierConfig {
  val kind = "io.l5d.ingress"
}

class IngressIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IngressIdentifierConfig]
  override val configId = IngressIdentifierConfig.kind
}

object IngressIdentifierInitializer extends IngressIdentifierInitializer
