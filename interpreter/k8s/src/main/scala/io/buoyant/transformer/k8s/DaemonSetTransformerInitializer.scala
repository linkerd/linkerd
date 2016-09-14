package io.buoyant.transformer.k8s

import com.twitter.finagle.Stack.Params
import com.twitter.finagle.param.Label
import com.twitter.finagle.{NameTree, Path}
import io.buoyant.config.types.Port
import io.buoyant.k8s.v1.Api
import io.buoyant.k8s.{ClientConfig, EndpointsNamer}
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}

class DaemonSetTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[DaemonSetTransformerConfig]
  override val configId = "io.l5d.daemonset"
}

case class DaemonSetTransformerConfig(
  k8sHost: Option[String],
  k8sPort: Option[Port],
  namespace: String,
  service: String,
  port: String
) extends TransformerConfig with ClientConfig {
  assert(namespace != null, "io.l5d.daemonset: namespace property is required")
  assert(service != null, "io.l5d.daemonset: service property is required")
  assert(port != null, "io.l5d.daemonset: port property is required")

  override def host: Option[String] = k8sHost
  override def portNum: Option[Int] = k8sPort.map(_.port)

  override def mk: NameTreeTransformer = {
    val client = mkClient(Params.empty)
    def mkNs(ns: String) = Api(client.configured(Label("daemonsetTransformer"))
      .newService(dst))
      .withNamespace(ns)
    val namer = new EndpointsNamer(Path.empty, mkNs)
    val daemonSet = namer.bind(NameTree.Leaf(Path.Utf8(namespace, port, service)))
    new DaemonSetTransformer(daemonSet)
  }
}
