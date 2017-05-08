package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, ServiceNamer}
import io.buoyant.k8s.v1.Api
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.k8s.external
 *   experimental: true
 *   host: localhost
 *   port: 8001
 * </pre>
 */
class K8sExternalInitializer extends NamerInitializer {
  val configClass = classOf[K8sExternalConfig]
  override def configId = "io.l5d.k8s.external"
}

object K8sExternalInitializer extends K8sExternalInitializer

case class K8sExternalConfig(
  host: Option[String],
  port: Option[Port],
  labelSelector: Option[String]
) extends NamerConfig with ClientConfig {

  @JsonIgnore
  override val experimentalRequired = true

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.k8s.external")

  @JsonIgnore
  def portNum = port.map(_.port)

  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = {
    val client = mkClient(params)
    def mkNs(ns: String) = {
      val label = param.Label(s"namer${prefix.show}/$ns")
      Api(client.configured(label).newService(dst)).withNamespace(ns)
    }
    new ServiceNamer(prefix, labelSelector, mkNs)
  }
}
