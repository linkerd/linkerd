package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.Path
import io.buoyant.config.{Config, ConfigInitializer}

trait TransformerConfig extends Config {

  def defaultPrefix: Path

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def prefix = Paths.TransformerPrefix ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(): NameTreeTransformer
}

trait TransformerInitializer extends ConfigInitializer
