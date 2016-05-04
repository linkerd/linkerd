package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.twitter.finagle.Announcer
import io.buoyant.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait AnnouncerConfig {
  def mk(): Announcer
}

trait AnnouncerInitializer extends ConfigInitializer
