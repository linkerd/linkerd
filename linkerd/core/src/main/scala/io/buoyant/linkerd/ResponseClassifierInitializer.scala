package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.service.ResponseClassifier
import io.buoyant.config.ConfigInitializer

abstract class ResponseClassifierInitializer extends ConfigInitializer

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "kind"
)
trait ResponseClassifierConfig {
  @JsonIgnore
  def mk: ResponseClassifier
}
