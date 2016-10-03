package io.buoyant.transformer
package perHost

import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.InetAddress

class LocalhostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[LocalhostTransformerConfig]
  override val configId = "io.l5d.localhost"
}

class LocalhostTransformerConfig extends TransformerConfig {

  override def mk(): NameTreeTransformer =
    new SubnetLocalTransformer(InetAddress.getLocalHost, Netmask("255.255.255.255"))
}
