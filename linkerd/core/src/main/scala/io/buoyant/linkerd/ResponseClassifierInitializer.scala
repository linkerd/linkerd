package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.service.ResponseClassifier
import io.buoyant.config.{Config, ConfigInitializer}

abstract class ResponseClassifierInitializer extends ConfigInitializer

trait ResponseClassifierConfig extends Config {
  @JsonIgnore
  def mk: ResponseClassifier
}
