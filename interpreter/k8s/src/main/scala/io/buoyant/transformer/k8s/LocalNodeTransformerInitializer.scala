package io.buoyant.transformer
package k8s

import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.InetAddress

class LocalNodeTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[LocalNodeTransformerConfig]
  override val configId = "io.l5d.k8s.localnode"
}

class LocalNodeTransformerConfig extends TransformerConfig {

  override def mk(): NameTreeTransformer = {
    val ip = sys.env.getOrElse(
      "POD_IP",
      throw new IllegalArgumentException("POD_IP env variable must be set to the pod's IP")
    )
    val local = InetAddress.getByName(ip)
    new SubnetLocalTransformer(local, Netmask("255.255.255.0"))
  }

}
