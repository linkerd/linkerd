package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.{ConfigInitializer, PolymorphicConfig}
import io.buoyant.namer.Paths

abstract class AnnouncerConfig extends PolymorphicConfig {

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def defaultPrefix: Path

  @JsonIgnore
  def prefix: Path = Paths.ConfiguredNamerPrefix ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(): Announcer = mk(Stack.Params.empty)

  @JsonIgnore
  def mk(params: Stack.Params): Announcer
}

trait AnnouncerInitializer extends ConfigInitializer
