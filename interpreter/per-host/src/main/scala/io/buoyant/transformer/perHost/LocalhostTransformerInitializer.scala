package io.buoyant.transformer
package perHost

import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.NetworkInterface
import scala.collection.JavaConverters._

class LocalhostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[LocalhostTransformerConfig]
  override val configId = "io.l5d.localhost"
}

class LocalhostTransformerConfig extends TransformerConfig {

  override def mk(): NameTreeTransformer = {
    val localIPs = for {
      interface <- NetworkInterface.getNetworkInterfaces.asScala
      if interface.isUp
      inet <- interface.getInetAddresses.asScala
      if !inet.isLoopbackAddress
    } yield inet
    new SubnetLocalTransformer(localIPs.toSeq, Netmask("255.255.255.255"))
  }

}
