package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.time._
import com.twitter.finagle._
import io.buoyant.config.types.Port
import io.buoyant.k8s._
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.k8s.istio
 *   experimental: true
 *   host: istio-manager.default.svc.cluster.local
 *   port: 8080
 *   pollIntervalMs: 5000
 * </pre>
 */
class IstioInitializer extends NamerInitializer {
  val configClass = classOf[IstioConfig]
  override def configId = "io.l5d.k8s.istio"
}

object IstioInitializer extends IstioInitializer

case class IstioConfig(
  host: Option[String],
  port: Option[Port],
  pollIntervalMs: Option[Long]
) extends NamerConfig with ClientConfig {

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override val DefaultHost = "istio-manager.default.svc.cluster.local"
  @JsonIgnore
  override val DefaultPort = 8080

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.k8s.istio")

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  private[this] val DefaultPollInterval = 5.seconds
  @JsonIgnore
  private[this] val pollInterval = pollIntervalMs.map(_.millis).getOrElse(DefaultPollInterval)

  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = {
    val client = mkClient(params)
    val label = param.Label(s"namer${prefix.show}")
    val sdsClient = new SdsClient(client.configured(label).newService(dst))
    new IstioNamer(sdsClient, prefix, pollInterval)
  }
}
