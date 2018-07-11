package io.buoyant.linkerd.protocol.h2.grpc

import com.twitter.finagle.buoyant.h2.service.H2Classifier
import io.buoyant.linkerd.ResponseClassifierInitializer
import io.buoyant.linkerd.protocol.h2._

class NeverRetryableConfig extends H2ClassifierConfig {
  override def mk: H2Classifier = GrpcClassifiers.NeverRetryable
}

class NeverRetryableInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[NeverRetryableConfig]
  override val configId = "io.l5d.h2.grpc.neverRetryable"
}

object NeverRetryableInitializer extends NeverRetryableInitializer

class AlwaysRetryableConfig extends H2ClassifierConfig {
  override def mk: H2Classifier = GrpcClassifiers.AlwaysRetryable
}

class AlwaysRetryableInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[AlwaysRetryableConfig]
  override val configId = "io.l5d.h2.grpc.alwaysRetryable"
}

object AlwaysRetryableInitializer extends AlwaysRetryableInitializer

class DefaultConfig extends H2ClassifierConfig {
  override def mk: H2Classifier = GrpcClassifiers.Default
}

class DefaultInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[DefaultConfig]
  override val configId = "io.l5d.h2.grpc.Default"
}

object DefaultInitializer extends DefaultInitializer

class CompliantConfig extends H2ClassifierConfig {
  override def mk: H2Classifier = GrpcClassifiers.Compliant
}

class CompliantInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[DefaultConfig]
  override val configId = "io.l5d.h2.grpc.Compliant"
}

object CompliantInitializer extends CompliantInitializer

// TODO: support parsing the status codes by name rather than by number?
class RetryableStatusCodesConfig(val retryableStatusCodes: Set[Int]) extends H2ClassifierConfig {
  override def mk: H2Classifier = new GrpcClassifiers.RetryableStatusCodes(retryableStatusCodes)
}

class RetryableStatusCodesInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableStatusCodesConfig]
  override val configId = "io.l5d.h2.grpc.retryableStatusCodes"
}

object RetryableStatusCodesInitializer extends RetryableStatusCodesInitializer
