package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.Path
import io.buoyant.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait TransformerConfig {

  def defaultPrefix: Path

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def prefix = TransformerConfig.Percent ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(): NameTreeTransformer
}

object TransformerConfig {
  val Percent = Path.Utf8("%")
}

trait TransformerInitializer extends ConfigInitializer
