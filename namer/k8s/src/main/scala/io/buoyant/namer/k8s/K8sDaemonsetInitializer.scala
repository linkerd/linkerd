package io.buoyant.namer.k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.param.Label
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, EndpointsNamer}
import io.buoyant.k8s.v1.Api
import io.buoyant.namer.{NamerConfig, NamerInitializer}

case class K8sDaemonsetConfig(
  k8sHost: Option[String],
  k8sPort: Option[Port],
  namespace: String,
  service: String,
  port: String
) extends NamerConfig with ClientConfig {
  @JsonIgnore
  override val defaultPrefix = Path.read("/#/io.l5d.k8s.ds")

  override def host: Option[String] = k8sHost
  override def portNum: Option[Int] = k8sPort.map(_.port)

  @JsonIgnore
  override protected def newNamer(params: Params): Namer = {
    val client = mkClient(Params.empty).configured(Label("daemonset-namer"))
    def mkNs(ns: String) = Api(client.newService(dst)).withNamespace(ns)
    val namer = new EndpointsNamer(Path.empty, None, mkNs)
    new K8sDaemonsetNamer(prefix, namer)
  }
}

class K8sDaemonsetInitializer extends NamerInitializer {
  override val configId = "io.l5d.k8s.ds"
  val configClass = classOf[K8sDaemonsetConfig]
}
