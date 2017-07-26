package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.service.{H2StreamClassifiers, H2StreamClassifier}
import io.buoyant.router.ClassifiedRetries

class RetryableIdempotent5XXConfig extends H2StreamClassifierConfig {
  def mk: H2StreamClassifier = H2StreamClassifiers.RetryableIdempotentFailures
}

class RetryableIdempotent5XXInitializer extends H2StreamClassifierInitializer {
  val configClass = classOf[RetryableIdempotent5XXConfig]
  override val configId = "io.l5d.h2.retryableIdempotent5XX"
}

object RetryableIdempotent5XXInitializer extends RetryableIdempotent5XXInitializer

class RetryableRead5XXConfig extends H2StreamClassifierConfig {
  def mk: H2StreamClassifier = H2StreamClassifiers.RetryableReadFailures
}

class RetryableRead5XXInitializer extends H2StreamClassifierInitializer {
  val configClass = classOf[RetryableRead5XXConfig]
  override val configId = "io.l5d.h2.retryableRead5XX"
}

object RetryableRead5XXInitializer extends RetryableRead5XXInitializer

class NonRetryable5XXConfig extends H2StreamClassifierConfig {
  def mk: H2StreamClassifier = H2StreamClassifiers.NonRetryableServerFailures
}

class NonRetryable5XXInitializer extends H2StreamClassifierInitializer {
  val configClass = classOf[NonRetryable5XXConfig]
  override val configId = "io.l5d.h2.nonRetryable5XX"
}

object NonRetryable5XXInitializer extends NonRetryable5XXInitializer

class AllSuccessfulConfig extends H2StreamClassifierConfig {
  def mk: H2StreamClassifier = H2StreamClassifiers.AllSuccessful
  // TODO: we still need h2 ClassifiedRetries
}

class AllSuccessfulInitializer extends H2StreamClassifierInitializer {
  val configClass = classOf[AllSuccessfulConfig]
  override val configId = "io.l5d.h2.allSuccessful"
}

object AllSuccessfulInitializer extends AllSuccessfulInitializer
