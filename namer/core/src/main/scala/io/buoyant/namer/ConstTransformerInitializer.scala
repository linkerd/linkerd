package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}

class ConstTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[ConstTransformerConfig]
  override val configId = "io.l5d.const"
}

object ConstTransformerInitializer extends ConstTransformerInitializer

case class ConstTransformerConfig(path: Path) extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.const")

  override def mk(params: Stack.Params): NameTreeTransformer = new ConstTransformer(prefix, path)
}
