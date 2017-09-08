package io.buoyant.linkerd.protocol.h2.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.Request
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.IngressCache
import io.buoyant.k8s.istio.identifiers.IngresssTrafficIdentifier
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.k8s.istio.{IstioIngressConfigurator, _}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.router.RoutingFactory.{BaseDtab, DstPrefix, Identifier, RequestIdentification}

class IstioIngressIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  namespace: Option[String],
  apiClient: Service[http.Request, http.Response],
  annotationClass: String,
  routeCache: RouteCache,
  clusterCache: ClusterCache,
  mixerClient: MixerClient
) extends Identifier[Request] {

  val istioIdentifier = new IngresssTrafficIdentifier[Request](pfx, baseDtab, routeCache, clusterCache, mixerClient, new H2IstioRequestHandler)

  private[this] val ingressCache = new IngressCache(namespace, apiClient, annotationClass)

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val matchingPath = ingressCache.matchPath(Some(req.authority), req.path)
    istioIdentifier.identify(H2IstioRequest(req), matchingPath)
  }
}

case class IstioIngressIdentifierConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String],
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port],
  mixerHost: Option[String],
  mixerPort: Option[Port]
) extends H2IdentifierConfig with IstioIngressConfigurator {
  @JsonIgnore
  override def portNum: Option[Int] = port.map(_.port)

  override def newIdentifier(params: Stack.Params) = {

    val DstPrefix(prefix) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]

    val k8sApiserverClient = mkK8sApiClient()

    new IstioIngressIdentifier(
      prefix,
      baseDtab,
      namespace,
      k8sApiserverClient.newService(dst),
      IngressAnnotationClass,
      mkRouteCache(apiserverHost, apiserverPort),
      mkClusterCache(discoveryHost, discoveryPort),
      mkMixerClient(mixerHost, mixerPort)
    )
  }
}

object IstioIngressIdentifierConfig {
  val kind = "io.l5d.k8s.istio-ingress"
}

class IstioIngressIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IstioIngressIdentifierConfig]
  override val configId = IstioIngressIdentifierConfig.kind
}

object IstioIngressIdentifierInitializer extends IstioIngressIdentifierInitializer
