package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.Frame
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2Classifiers, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.service.ResponseClass
import com.twitter.util.Return
import io.buoyant.router.ClassifiedRetries

class RetryableIdempotent5XXConfig extends H2ClassifierConfig {
  def mk: H2Classifier =
    H2Classifiers.RetryableIdempotentFailures
}

class RetryableIdempotent5XXInitializer extends H2ClassifierInitializer {
  val configClass = classOf[RetryableIdempotent5XXConfig]
  override val configId = "io.l5d.h2.retryableIdempotent5XX"
}

object RetryableIdempotent5XXInitializer extends RetryableIdempotent5XXInitializer

class RetryableRead5XXConfig extends H2ClassifierConfig {
  def mk: H2Classifier =
    H2Classifiers.RetryableReadFailures
}

class RetryableRead5XXInitializer extends H2ClassifierInitializer {
  val configClass = classOf[RetryableRead5XXConfig]
  override val configId = "io.l5d.h2.retryableRead5XX"
}

object RetryableRead5XXInitializer extends RetryableRead5XXInitializer

class NonRetryable5XXConfig extends H2ClassifierConfig {
  def mk: H2Classifier =
    H2Classifiers.NonRetryableServerFailures
}

class NonRetryable5XXInitializer extends H2ClassifierInitializer {
  val configClass = classOf[NonRetryable5XXConfig]
  override val configId = "io.l5d.h2.nonRetryable5XX"
}

object NonRetryable5XXInitializer extends NonRetryable5XXInitializer

class AllSuccessfulConfig extends H2ClassifierConfig {
  def mk: H2Classifier = H2Classifiers.AllSuccessful
}

class AllSuccessfulInitializer extends H2ClassifierInitializer {
  val configClass = classOf[AllSuccessfulConfig]
  override val configId = "io.l5d.h2.allSuccessful"
}

object AllSuccessfulInitializer extends AllSuccessfulInitializer
