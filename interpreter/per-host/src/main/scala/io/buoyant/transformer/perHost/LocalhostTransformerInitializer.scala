package io.buoyant.transformer
package perHost

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.NetworkInterface
import scala.jdk.CollectionConverters._

class LocalhostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[LocalhostTransformerConfig]
  override val configId = "io.l5d.localhost"
}

class LocalhostTransformerConfig extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.localhost")

  @JsonIgnore
  override def mk(params: Stack.Params): NameTreeTransformer = {
    val localIPs = for {
      interface <- NetworkInterface.getNetworkInterfaces.asScala
      if interface.isUp
      inet <- interface.getInetAddresses.asScala
    } yield inet
    new SubnetLocalTransformer(prefix, localIPs.toSeq, Netmask("255.255.255.255"))
  }

}
