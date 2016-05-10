package io.l5d.experimental

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import io.buoyant.k8s.v1.Api
import io.buoyant.k8s._
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import scala.io.Source

/**
 * Supports namer configurations in the form:
 *
 * <pre>
 * namers:
 * - kind: io.l5d.experimental.k8s
 *   host: localhost
 *   port: 8001
 * </pre>
 */
class K8sInitializer extends NamerInitializer {
  val configClass = classOf[k8s]
}

object K8sInitializer extends K8sInitializer

case class k8s(
  host: Option[String],
  port: Option[Port]
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
    val client = mkClient(params)
    def mkNs(ns: String) = {
      val label = param.Label(s"namer${prefix.show}/$ns")
      Api(client.configured(label).newService(dst)).withNamespace(ns)
    }
    new EndpointsNamer(prefix, mkNs)
  }
}
