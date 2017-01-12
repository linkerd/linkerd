package io.buoyant.telemetry

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack
import io.buoyant.config.{Config, ConfigInitializer}

/**
 * Telemeter plugins describe how to load TelemeterConfig items.
 */
trait TelemeterInitializer extends ConfigInitializer {
  type Config <: TelemeterConfig
  def configClass: Class[Config]
}

trait TelemeterConfig extends Config {
  @JsonIgnore def mk(params: Stack.Params): Telemeter
}
