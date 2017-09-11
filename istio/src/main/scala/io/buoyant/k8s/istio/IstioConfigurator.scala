package io.buoyant.k8s.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Address, Name}
import com.twitter.logging.Logger
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClientConfig
import io.buoyant.k8s.istio.mixer.MixerClient

trait IstioConfigurator {
  @JsonIgnore
  protected val log = Logger.get(this.getClass.getSimpleName)

  protected def mkMixerClient(mixerHost: Option[String], mixerPort: Option[Port]) = {

    val host = mixerHost.getOrElse(DefaultMixerHost)

    val port = mixerPort.map(_.port).getOrElse(DefaultMixerPort)

    log.info("connecting to Istio Mixer at %s:%d", host, port)

    val mixerDst = Name.bound(Address(host, port))

    val mixerService = H2.client
      .withParams(H2.client.params)
      .newService(mixerDst, this.getClass.getSimpleName)

    MixerClient(mixerService)
  }

  protected def mkRouteCache(apiserverHost: Option[String], apiserverPort: Option[Port]) = {
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    RouteCache.getManagerFor(host, port)
  }

  protected def mkClusterCache(discoveryHost: Option[String], discoveryPort: Option[Port]) = {
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )

    new ClusterCacheBackedByApi(discoveryClient)
  }
}

trait IstioIngressConfigurator extends IstioConfigurator with ClientConfig {
  protected def mkK8sApiClient() = mkClient(Params.empty).configured(Label("ingress-identifier"))
}