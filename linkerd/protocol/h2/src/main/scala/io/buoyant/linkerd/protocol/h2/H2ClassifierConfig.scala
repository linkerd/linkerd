package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes}
import com.twitter.finagle.buoyant.h2.service.H2Classifier
import io.buoyant.config.{ConfigInitializer, PolymorphicConfig}

abstract class H2ClassifierInitializer extends ConfigInitializer

@JsonSubTypes(Array(
  new Type(
    value = classOf[RetryableIdempotent5XXConfig],
    name = "io.l5d.h2.retryableIdempotent5XX"
  ),
  new Type(
    value = classOf[RetryableRead5XXConfig],
    name = "io.l5d.h2.retryableRead5XX"
  ),
  new Type(
    value = classOf[NonRetryable5XXConfig],
    name = "io.l5d.h2.nonRetryable5XX"
  ),
  new Type(
    value = classOf[AllSuccessfulConfig],
    name = "io.l5d.h2.allSuccessful"
  )
))
abstract class H2ClassifierConfig extends PolymorphicConfig {
  @JsonIgnore
  def mk: H2Classifier
}
