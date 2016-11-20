package io.buoyant.namer

import com.twitter.finagle.Path

class ConstTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[ConstTransformerConfig]
  override val configId = "io.l5d.const"
}

object ConstTransformerInitializer extends ConstTransformerInitializer

case class ConstTransformerConfig(path: Path) extends TransformerConfig {
  override def mk(): NameTreeTransformer = new ConstTransformer(path)
}
