package io.buoyant.transformer.perHost

import io.buoyant.config.types.Port
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}

class PortTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[PortTransformerConfig]
  override val configId = "io.l5d.port"
}

case class PortTransformerConfig(port: Port) extends TransformerConfig {
  override def mk(): NameTreeTransformer = new PortTransformer(port.port)
}
