package io.buoyant.transformer.perHost

import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}

class LocalhostTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[LocalhostTransformerConfig]
  override val configId = "io.l5d.localhost"
}

class LocalhostTransformerConfig extends TransformerConfig {
  override def mk: NameTreeTransformer = new LocalhostTransformer
}
