package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.Path
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}
import io.buoyant.namer.Paths

abstract class AnnouncerConfig extends PolymorphicConfig {

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def defaultPrefix: Path

  @JsonIgnore
  def prefix: Path = Paths.ConfiguredNamerPrefix ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(): Announcer
}

trait AnnouncerInitializer extends ConfigInitializer
