package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.param.Label
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.{Duration, Future, Timer}
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, IngressCache, Ns, v1beta1}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest, _}

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
    val hostHeader = req.headerMap.get("Host")
    val matchingPaths = ingressWithCache.get("", None).getMatchingPath(hostHeader, req.path)
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
  k8sHost: Option[String],
  k8sPort: Option[Port]
) extends HttpIdentifierConfig with ClientConfig {
  @JsonIgnore
  override def host: Option[String] = k8sHost

  @JsonIgnore
  override def portNum: Option[Int] = k8sPort.map(_.port)

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val client = mkClient(Params.empty).configured(Label("ingress-identifier"))
    val mkApi = (ns: String) => v1beta1.Api(client.newService(dst))
    new IngressIdentifier(prefix, baseDtab, mkApi)
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
