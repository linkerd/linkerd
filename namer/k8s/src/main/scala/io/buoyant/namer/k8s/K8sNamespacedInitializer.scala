package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{param, Namer, Stack, Path}
import io.buoyant.config.types.Port
import io.buoyant.k8s.{SingleNsNamer, ClientConfig}
import io.buoyant.k8s.v1.Api
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class K8sNamespacedInitializer extends NamerInitializer {
  val configClass = classOf[K8sNamespacedConfig]
  override def configId = "io.l5d.k8s.ns"
}

object K8sNamespacedInitializer extends K8sNamespacedInitializer

case class K8sNamespacedConfig(
  host: Option[String],
  port: Option[Port],
  envVar: Option[String],
  labelSelector: Option[String]
) extends NamerConfig with ClientConfig {

  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.k8s.ns")

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  def nsName = sys.env.get(envVar.getOrElse("POD_NAMESPACE"))

  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = {
    assert(nsName.isDefined, "could not find namespace for envVar")

    val client = mkClient(params)
    def mkNs(ns: String) = {
      val label = param.Label(s"namer${prefix.show}/$ns")
      Api(client.configured(label).newService(dst)).withNamespace(ns)
    }
    new SingleNsNamer(prefix, labelSelector, nsName.get, mkNs)
  }
}
