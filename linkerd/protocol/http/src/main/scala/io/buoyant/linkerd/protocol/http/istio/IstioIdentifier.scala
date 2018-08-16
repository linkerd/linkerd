package io.buoyant.linkerd.protocol.http.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.logging.Logger
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.identifiers.InternalTrafficIdentifier
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{Identifier, RequestIdentification}

class IstioIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  routeCache: RouteCache,
  clusterCache: ClusterCache,
  mixerClient: MixerClient
)
  extends Identifier[Request] {

  val internalIstioIdentifier = new InternalTrafficIdentifier[Request](pfx, baseDtab, routeCache, clusterCache, mixerClient, new HttpIstioRequestHandler)

  override def apply(originalRequest: Request): Future[RequestIdentification[Request]] = {
    val req = HttpIstioRequest(originalRequest, None)
    req.req.host match {
      case Some(host) => internalIstioIdentifier.identify(req)
      case None => throw new IllegalArgumentException("no host found for request")
    }
  }
}

case class IstioIdentifierConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port],
  mixerHost: Option[String],
  mixerPort: Option[Port]
) extends HttpIdentifierConfig {
  import IstioServices._

  @JsonIgnore
  val _ = Logger.get(this.getClass.getName).warning("Istio HTTP Identifier has been deprecated since version 1.4.7")

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base,
    routerParams: Stack.Params = Stack.Params.empty
  ): Identifier[Request] = {

    new IstioIdentifier(
      prefix,
      baseDtab,
      mkRouteCache(apiserverHost, apiserverPort),
      mkClusterCache(discoveryHost, discoveryPort),
      mkMixerClient(mixerHost, mixerPort)
    )
  }
}

object IstioIdentifierConfig {
  val kind = "io.l5d.k8s.istio"
}

class IstioIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IstioIdentifierConfig]
  override val configId = IstioIdentifierConfig.kind
}

object IstioIdentifierInitializer extends IstioIdentifierInitializer
