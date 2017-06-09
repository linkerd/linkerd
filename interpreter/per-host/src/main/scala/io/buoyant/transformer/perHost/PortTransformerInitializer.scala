package io.buoyant.transformer.perHost

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.types.Port
import io.buoyant.namer.{NameTreeTransformer, TransformerConfig, TransformerInitializer}

class PortTransformerInitializer extends TransformerInitializer {
  val configClass = classOf[PortTransformerConfig]
  override val configId = "io.l5d.port"
}

case class PortTransformerConfig(port: Port) extends TransformerConfig {

  @JsonIgnore
  val defaultPrefix = Path.read(s"/io.l5d.port/${port.port}")

  override def mk(params: Stack.Params): NameTreeTransformer = new PortTransformer(prefix, port.port)
}
