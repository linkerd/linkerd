package io.buoyant.k8s.istio

import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.{Address, Name}
import com.twitter.logging.Logger
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.mixer.MixerClient

object IstioServices {
  protected val log = Logger()

  def mkMixerClient(mixerHost: Option[String], mixerPort: Option[Port]): MixerClient = {

    val host = mixerHost.getOrElse(DefaultMixerHost)

    val port = mixerPort.map(_.port).getOrElse(DefaultMixerPort)

    log.info("connecting to Istio Mixer at %s:%d", host, port)

    val mixerDst = Name.bound(Address(host, port))

    val mixerService = H2.client
      .withParams(H2.client.params)
      .newService(mixerDst, this.getClass.getSimpleName)

    MixerClient(mixerService)
  }

  def mkRouteCache(apiserverHost: Option[String], apiserverPort: Option[Port]): RouteCache = {
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    RouteCache.getManagerFor(host, port)
  }

  def mkClusterCache(discoveryHost: Option[String], discoveryPort: Option[Port]): ClusterCacheBackedByApi = {
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )

    new ClusterCacheBackedByApi(discoveryClient)
  }
}