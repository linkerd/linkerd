package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.service.ResponseClassifier
import io.buoyant.linkerd.{ResponseClassifierConfig, ResponseClassifierInitializer}
import io.buoyant.router.ClassifiedRetries

class RetryableIdempotent5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.RetryableIdempotentFailures
}

class RetryableIdempotent5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableIdempotent5XXConfig]
  override val configId = "io.l5d.h2.retryableIdempotent5XX"
}

object RetryableIdempotent5XXInitializer extends RetryableIdempotent5XXInitializer

class RetryableRead5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.RetryableReadFailures
}

class RetryableRead5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableRead5XXConfig]
  override val configId = "io.l5d.h2.retryableRead5XX"
}

object RetryableRead5XXInitializer extends RetryableRead5XXInitializer

class NonRetryable5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.NonRetryableServerFailures
}

class NonRetryable5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[NonRetryable5XXConfig]
  override val configId = "io.l5d.h2.nonRetryable5XX"
}

object NonRetryable5XXInitializer extends NonRetryable5XXInitializer

class AllSuccessfulConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ClassifiedRetries.Default
}

class AllSuccessfulInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[AllSuccessfulConfig]
  override val configId = "io.l5d.h2.allSuccessful"
}

object AllSuccessfulInitializer extends AllSuccessfulInitializer
