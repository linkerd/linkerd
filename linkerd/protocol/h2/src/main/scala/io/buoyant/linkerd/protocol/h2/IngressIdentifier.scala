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
import io.buoyant.k8s.{IngressCache, ClientConfig, Ns, v1beta1}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest, _}

object IngressIdentifier {
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
    val matchingPaths = ingressWithCache.get("", None).getMatchingRules(hostHeader, req.path)
    matchingPaths.flatMap { paths =>
      paths.headOption match {
        case None => Future.value(unidentified)
        case Some(a) =>
          val path = pfx ++ Path.Utf8(a.namespace, a.port, a.svc)
          val dst = Dst.Path(path, baseDtab(), Dtab.local)
          Future.value(new IdentifiedRequest(dst, req))
      }
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
  override def newIdentifier(params: Stack.Params) = {
    val client = mkClient(Params.empty).configured(Label("ingress-identifier"))
    val mkApi = (ns: String) => v1beta1.Api(client.newService(dst))
    IngressIdentifier.mk(params, mkApi)
  }
}

object IngressIdentifierConfig {
  val kind = "io.l5d.h2.ingress"
}

class IngressIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IngressIdentifierConfig]
  override val configId = IngressIdentifierConfig.kind
}

object IngressIdentifierInitializer extends IngressIdentifierInitializer
