package io.buoyant.namer

import com.twitter.finagle.Path

class ReplaceTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[ReplaceTransformerConfig]
  override val configId = "io.l5d.replace"
}

case class ReplaceTransformerConfig(path: Path) extends TransformerConfig {
  override def mk(): NameTreeTransformer = new ReplaceTransformer(path)
}
