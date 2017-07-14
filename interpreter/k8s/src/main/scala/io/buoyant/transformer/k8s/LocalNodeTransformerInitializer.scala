package io.buoyant.transformer
package k8s

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import io.buoyant.namer._
import java.net.InetAddress

class LocalNodeTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[LocalNodeTransformerConfig]
  override val configId = "io.l5d.k8s.localnode"
}

case class LocalNodeTransformerConfig(hostNetwork: Option[Boolean])
  extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.k8s.localnode")

  @JsonIgnore
  override def mk(params: Stack.Params): NameTreeTransformer = {
    if (hostNetwork.getOrElse(false)) {
      val nodeName = sys.env.getOrElse(
        "NODE_NAME",
        throw new IllegalArgumentException(
          "NODE_NAME env variable must be set to the node's name"
        )
      )
      new MetadataFiltertingNameTreeTransformer(prefix ++ Path.Utf8(nodeName), Metadata.nodeName, nodeName)
    } else {
      val ip = sys.env.getOrElse(
        "POD_IP",
        throw new IllegalArgumentException(
          "POD_IP env variable must be set to the pod's IP"
        )
      )
      val local = InetAddress.getByName(ip)
      new SubnetLocalTransformer(prefix ++ Path.Utf8(ip), Seq(local), Netmask("255.255.255.0"))
    }
  }

}
