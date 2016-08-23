package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.Path
import io.buoyant.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait AnnouncerConfig {

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def defaultPrefix: Path

  @JsonIgnore
  def prefix: Path = AnnouncerConfig.hash ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def mk(): Announcer
}

object AnnouncerConfig {
  val hash = Path.Utf8("#")
}

trait AnnouncerInitializer extends ConfigInitializer
