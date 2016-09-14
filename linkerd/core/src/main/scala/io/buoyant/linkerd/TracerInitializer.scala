package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.tracing.Tracer
import io.buoyant.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait TracerConfig {

  /**
   * Construct a tracer.
   */
  @JsonIgnore
  def newTracer(): Tracer
}

abstract class TracerInitializer extends ConfigInitializer
