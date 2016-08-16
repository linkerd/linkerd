package io.buoyant.telemetry

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import io.buoyant.config.ConfigInitializer

/**
 * Telemeter plugins describe how to
 */
trait TelemeterInitializer extends ConfigInitializer {
  type Config <: TelemeterConfig
  def configClass: Class[Config]
}

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "kind"
)
trait TelemeterConfig {
  @JsonIgnore def mk(): Telemeter
}
