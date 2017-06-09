package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import io.buoyant.config.types.Port
import io.buoyant.k8s._
import io.buoyant.k8s.v1.Api
import io.buoyant.namer.{NamerConfig, NamerInitializer}

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.k8s
 *   host: localhost
 *   port: 8001
 * </pre>
 */
class K8sInitializer extends NamerInitializer {
  val configClass = classOf[K8sConfig]
  override def configId = "io.l5d.k8s"
}

object K8sInitializer extends K8sInitializer

case class K8sConfig(
  host: Option[String],
  port: Option[Port],
  labelSelector: Option[String]
) extends NamerConfig with ClientConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.k8s")

  @JsonIgnore
  def portNum = port.map(_.port)

  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = {
    val client = mkClient(params).withLabel("client")
    def mkNs(ns: String) = {
      Api(client.newService(dst)).withNamespace(ns)
    }
    new MultiNsNamer(prefix, labelSelector, mkNs)
  }
}
