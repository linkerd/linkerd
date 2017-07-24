package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.h2.service.ResponseClassifier
import io.buoyant.config.{ConfigInitializer, PolymorphicConfig}

abstract class ResponseClassifierInitializer extends ConfigInitializer
abstract class ResponseClassifierConfig extends PolymorphicConfig {
  @JsonIgnore
  def mk: ResponseClassifier
}
