package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.tracing.Tracer
import io.buoyant.config.{Config, ConfigInitializer}

trait TracerConfig extends Config {

  /**
   * Construct a tracer.
   */
  @JsonIgnore
  def newTracer(): Tracer
}

abstract class TracerInitializer extends ConfigInitializer
