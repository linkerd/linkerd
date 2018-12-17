package io.buoyant.linkerd.protocol.h2.grpc

import com.twitter.finagle.buoyant.h2.service.H2Classifier
import io.buoyant.linkerd.ResponseClassifierInitializer
import io.buoyant.linkerd.protocol.h2._
import io.buoyant.grpc.runtime.GrpcStatus
import io.buoyant.grpc.runtime.GrpcStatus.Ok

abstract class GrpcClassifierConfig extends H2ClassifierConfig {
  var successStatusCodes: Option[Set[Int]] = None
  def _successStatusCodes = successStatusCodes.getOrElse(Set(0))
}

class NeverRetryableConfig extends GrpcClassifierConfig {
  override def mk: H2Classifier = new GrpcClassifiers.NeverRetryable(_successStatusCodes)
}

class NeverRetryableInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[NeverRetryableConfig]
  override val configId = "io.l5d.h2.grpc.neverRetryable"
}

object NeverRetryableInitializer extends NeverRetryableInitializer

class AlwaysRetryableConfig extends GrpcClassifierConfig {
  override def mk: H2Classifier = new GrpcClassifiers.AlwaysRetryable(_successStatusCodes)
}

class AlwaysRetryableInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[AlwaysRetryableConfig]
  override val configId = "io.l5d.h2.grpc.alwaysRetryable"
}

object AlwaysRetryableInitializer extends AlwaysRetryableInitializer

class DefaultConfig extends GrpcClassifierConfig {
  override def mk: H2Classifier = new GrpcClassifiers.Default(_successStatusCodes)
}

class DefaultInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[DefaultConfig]
  override val configId = "io.l5d.h2.grpc.default"
}

object DefaultInitializer extends DefaultInitializer

class CompliantConfig extends GrpcClassifierConfig {
  override def mk: H2Classifier = new GrpcClassifiers.Compliant(_successStatusCodes)
}

class CompliantInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[DefaultConfig]
  override val configId = "io.l5d.h2.grpc.compliant"
}

object CompliantInitializer extends CompliantInitializer

// TODO: support parsing the status codes by name rather than by number?
class RetryableStatusCodesConfig(val retryableStatusCodes: Set[Int]) extends GrpcClassifierConfig {
  override def mk: H2Classifier = new GrpcClassifiers.RetryableStatusCodes(retryableStatusCodes, _successStatusCodes)
}

class RetryableStatusCodesInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableStatusCodesConfig]
  override val configId = "io.l5d.h2.grpc.retryableStatusCodes"
}

object RetryableStatusCodesInitializer extends RetryableStatusCodesInitializer
