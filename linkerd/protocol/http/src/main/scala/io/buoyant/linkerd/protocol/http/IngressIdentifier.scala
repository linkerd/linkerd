package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.param.Label
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{http, Service, Dtab, Path}
import com.twitter.util.{Duration, Future, Timer}
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, IngressCache, Ns, v1beta1}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest, _}

class IngressIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  namespace: Option[String],
  apiClient: Service[http.Request, http.Response]
) extends Identifier[Request] {

  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"no ingress rule matches")

  private[this] val ingressWithCache = IngressCache.mk(namespace, apiClient)

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val hostHeader = req.headerMap.get("Host")
    val matchingPaths = ingressWithCache.get(None, None).getMatchingPath(hostHeader, req.path)
    matchingPaths.flatMap { paths =>
      paths.headOption match {
        case None => Future.value(unidentified)
        case Some(a) =>
          val path = pfx ++ Path.Utf8(a.namespace, a.port, a.svc)
          val dst = Dst.Path(path, baseDtab(), Dtab.local)
          req.uri = "/"
          Future.value(new IdentifiedRequest(dst, req))
      }
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
    new IngressIdentifier(prefix, baseDtab, namespace, client.newService(dst))
  }
}

object IngressIdentifierConfig {
  val kind = "io.l5d.http.ingress"
}

class IngressIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IngressIdentifierConfig]
  override val configId = IngressIdentifierConfig.kind
}

object IngressIdentifierInitializer extends IngressIdentifierInitializer
