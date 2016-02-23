package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.tracing.Tracer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
trait TracerConfig {
  /**
   * Construct a tracer.
   */
  @JsonIgnore
  def newTracer(): Tracer
}

abstract class TracerInitializer extends ConfigInitializer