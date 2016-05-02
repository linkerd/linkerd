package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.tracing.{debugTrace => fDebugTrace, Tracer}
import io.buoyant.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
trait TracerConfig {

  @JsonProperty("debugTrace")
  var _debugTrace: Option[Boolean] = None

  // default to {com.twitter.finagle.tracing.debugTrace} if not set
  @JsonIgnore
  def debugTrace: Boolean = _debugTrace.getOrElse(fDebugTrace())

  /**
   * Construct a tracer.
   */
  @JsonIgnore
  def newTracer(): Tracer
}

abstract class TracerInitializer extends ConfigInitializer
