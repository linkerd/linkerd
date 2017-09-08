package io.buoyant.linkerd.protocol.h2.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.identifiers.InternalTrafficIdentifier
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.router.RoutingFactory.{BaseDtab, DstPrefix, Identifier, RequestIdentification}

class IstioIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  routeCache: RouteCache,
  clusterCache: ClusterCache,
  mixerClient: MixerClient
)
  extends Identifier[Request] {
  val istioIdentifier = new InternalTrafficIdentifier[Request](pfx, baseDtab, routeCache, clusterCache, mixerClient, new H2IstioRequestHandler)

  override def apply(originalRequest: Request): Future[RequestIdentification[Request]] = {
    val req = H2IstioRequest(originalRequest)
    istioIdentifier.identify(req)
  }

}

case class IstioIdentifierConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port],
  mixerHost: Option[String],
  mixerPort: Option[Port]
) extends H2IdentifierConfig with IstioConfigurator {

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) = {

    val DstPrefix(prefix) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]

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
