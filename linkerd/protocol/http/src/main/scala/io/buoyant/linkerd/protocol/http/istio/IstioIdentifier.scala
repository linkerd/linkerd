package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
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
    val req = HttpIstioRequest(originalRequest)
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
) extends HttpIdentifierConfig with IstioConfigurator {

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
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
