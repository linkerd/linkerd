package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.h2.service.H2Classifier
import io.buoyant.config.PolymorphicConfig

abstract class H2ClassifierConfig extends PolymorphicConfig {
  @JsonIgnore
  def mk: H2Classifier
}
