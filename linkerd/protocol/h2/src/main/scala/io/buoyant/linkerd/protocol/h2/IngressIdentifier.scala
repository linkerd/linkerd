package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Headers, Request}
import com.twitter.finagle.param.Label
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s._
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.router.RoutingFactory._

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
    val headerToMatch = req.headers.get(Headers.Authority)
    val matchingPath = ingressCache.matchPath(headerToMatch, req.path)
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
) extends H2IdentifierConfig with ClientConfig {
  override def portNum: Option[Int] = port.map(_.port)

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) = {
    val DstPrefix(pfx) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
    val client = mkClient(params).configured(Label("ingress-identifier"))
    new IngressIdentifier(pfx, baseDtab, namespace, client.newService(dst), "linkerd")
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
