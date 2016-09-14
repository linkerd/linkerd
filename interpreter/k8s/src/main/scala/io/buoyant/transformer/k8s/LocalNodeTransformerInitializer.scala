package io.buoyant.transformer.k8s

import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.InetAddress

class LocalNodeTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[DaemonSetTransformerConfig]
  override val configId = "io.l5d.localnode"
}

class LocalNodeTransformerConfig extends TransformerConfig {

  override def mk: NameTreeTransformer = {
    val ip = sys.env.getOrElse(
      "POD_IP",
      throw new IllegalArgumentException("POD_IP env variable must be set to the pod's IP")
    )
    new LocalNodeTransformer(InetAddress.getByName(ip))
  }
}
