package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.{ConfigInitializer, PolymorphicConfig}

abstract class TransformerConfig extends PolymorphicConfig {

  def defaultPrefix: Path

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def prefix = Paths.TransformerPrefix ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(params: Stack.Params): NameTreeTransformer
}

trait TransformerInitializer extends ConfigInitializer
