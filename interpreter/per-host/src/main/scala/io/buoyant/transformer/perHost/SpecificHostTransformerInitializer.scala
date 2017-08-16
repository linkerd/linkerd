package io.buoyant.transformer
package perHost

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}
import java.net.InetAddress

class SpecificHostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[SpecificHostTransformerConfig]
  override val configId = "io.l5d.specificHost"
}

case class SpecificHostTransformerConfig(host: String) extends TransformerConfig {
  assert(host != null, "io.l5d.specificHost: host property is required")

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.specificHost")

  @JsonIgnore
  override def mk(params: Stack.Params): NameTreeTransformer = {
    new SubnetLocalTransformer(prefix, Seq(InetAddress.getByName(host)), Netmask("255.255.255.255"))
  }

}
