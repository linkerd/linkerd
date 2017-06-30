package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.{DiscoveryClient, IstioNamer}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.k8s.istio
 *   experimental: true
 *   host: istio-pilot.default.svc.cluster.local
 *   port: 8080
 * </pre>
 */
class IstioInitializer extends NamerInitializer {
  val configClass = classOf[IstioConfig]
  override def configId = "io.l5d.k8s.istio"
}

object IstioInitializer extends IstioInitializer

case class IstioConfig(
  host: Option[String],
  port: Option[Port]
) extends NamerConfig {
  import io.buoyant.k8s.istio._

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.k8s.istio")

  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = {
    val label = param.Label(s"namer${prefix.show}")
    val discoveryClient = DiscoveryClient(
      host.getOrElse(DefaultDiscoveryHost),
      port.map(_.port).getOrElse(DefaultDiscoveryPort)
    )
    new IstioNamer(discoveryClient, prefix)
  }
}
