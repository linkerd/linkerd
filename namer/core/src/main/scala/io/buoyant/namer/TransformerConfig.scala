package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.Path
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}

abstract class TransformerConfig extends PolymorphicConfig {

  def defaultPrefix: Path

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def prefix = Paths.TransformerPrefix ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(): NameTreeTransformer
}

trait TransformerInitializer extends ConfigInitializer
