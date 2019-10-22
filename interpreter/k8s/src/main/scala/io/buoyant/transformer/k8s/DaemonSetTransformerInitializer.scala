package io.buoyant.transformer
package k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.param.Label
import com.twitter.finagle.{NameTree, Path, param}
import io.buoyant.config.types.Port
import io.buoyant.k8s.v1.Api
import io.buoyant.k8s.{ClientConfig, EndpointsNamer, MultiNsNamer}
import io.buoyant.namer.{Metadata, NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.InetAddress

class DaemonSetTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[DaemonSetTransformerConfig]
  override val configId = "io.l5d.k8s.daemonset"
}

case class DaemonSetTransformerConfig(
  k8sHost: Option[String],
  k8sPort: Option[Port],
  namespace: String,
  service: String,
  port: String,
  hostNetwork: Option[Boolean]
) extends TransformerConfig with ClientConfig {
  assert(namespace != null, "io.l5d.k8s.daemonset: namespace property is required")
  assert(service != null, "io.l5d.k8s.daemonset: service property is required")
  assert(port != null, "io.l5d.k8s.daemonset: port property is required")

  @JsonIgnore
  override def host: Option[String] = k8sHost

  @JsonIgnore
  override def portNum: Option[Int] = k8sPort.map(_.port)

  @JsonIgnore
  val defaultPrefix = Path.read(s"/io.l5d.k8s.daemonset/$namespace/$port/$service")

  @JsonIgnore
  private[this] val netmask = InetAddress.getByName("255.255.255.0")

  @JsonIgnore
  override def mk(params: Params): NameTreeTransformer = {
    val client = mkClient(params).configured(param.Label("client"))
    def mkNs(ns: String) = Api(client.newService(dst)).withNamespace(ns)

    val namer = new MultiNsNamer(prefix, None, mkNs)

    val daemonSet = namer.bind(NameTree.Leaf(Path.Utf8(namespace, port, service)))
    if (hostNetwork.getOrElse(false))
      new MetadataGatewayTransformer(prefix, daemonSet, Metadata.nodeName, namer.adminHandlers)
    else
      new SubnetGatewayTransformer(prefix, daemonSet, Netmask("255.255.255.0"), namer.adminHandlers)
  }
}
