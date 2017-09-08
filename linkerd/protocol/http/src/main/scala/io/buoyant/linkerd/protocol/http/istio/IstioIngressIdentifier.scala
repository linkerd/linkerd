package io.buoyant.linkerd.protocol.http.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.http.Request
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.IngressCache
import io.buoyant.k8s.istio._
import io.buoyant.k8s.istio.identifiers.{IngresssTrafficIdentifier, IstioProtocolSpecificRequestHandler}
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{Identifier, RequestIdentification}

class IstioIngressIdentifier(
  pfx: Path,
  baseDtab: () => Dtab,
  namespace: Option[String],
  apiClient: Service[http.Request, http.Response],
  annotationClass: String,
  routeCache: RouteCache,
  clusterCache: ClusterCache,
  mixerClient: MixerClient,
  requestHandler: IstioProtocolSpecificRequestHandler[Request]
) extends Identifier[Request] {
  val internalIstioIdentifier = new IngresssTrafficIdentifier[Request](pfx, baseDtab, routeCache, clusterCache, mixerClient, new HttpIstioRequestHandler)
  private[this] val ingressCache = new IngressCache(namespace, apiClient, annotationClass)

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val matchingPath = ingressCache.matchPath(req.host, req.path)
    internalIstioIdentifier.identify(HttpIstioRequest(req), matchingPath)
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
) extends HttpIdentifierConfig with IstioIngressConfigurator {
  @JsonIgnore
  override def portNum: Option[Int] = port.map(_.port)

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {

    val k8sApiserverClient: Http.Client = mkK8sApiClient()

    new IstioIngressIdentifier(
      prefix,
      baseDtab,
      namespace,
      k8sApiserverClient.newService(dst),
      IngressAnnotationClass,
      mkRouteCache(host, port),
      mkClusterCache(discoveryHost, discoveryPort),
      mkMixerClient(mixerHost, mixerPort),
      new HttpIstioRequestHandler
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
