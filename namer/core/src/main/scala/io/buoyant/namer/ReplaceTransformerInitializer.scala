package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Path

class ReplaceTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[ReplaceTransformerConfig]
  override val configId = "io.l5d.replace"
}

case class ReplaceTransformerConfig(path: Path) extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read("/io.l5d.replace")

  @JsonIgnore
  override def mk(): NameTreeTransformer = new ReplaceTransformer(prefix, path)
}
