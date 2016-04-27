package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.service.{ResponseClass, ReqRep, ResponseClassifier}
import com.twitter.util.{NonFatal, Return, Throw}
import io.buoyant.config.ConfigInitializer
import io.buoyant.linkerd.{ResponseClassifierConfig, ResponseClassifierInitializer}

object ResponseClassifiers {

  object Requests {

    case class ByMethod(methods: Set[Method]) {
      def unapply(req: Request): Boolean = methods.contains(req.method)
    }

    /** Matches read-only requests */
    val ReadOnly = ByMethod(Set(
      Method.Get,
      Method.Head,
      Method.Options,
      Method.Trace
    ))

    /**
     * Matches idempotent requests.
     *
     * Per RFC2616:
     *
     *   Methods can also have the property of "idempotence" in that
     *   (aside from error or expiration issues) the side-effects of N >
     *   0 identical requests is the same as for a single request. The
     *   methods GET, HEAD, PUT and DELETE share this property. Also,
     *   the methods OPTIONS and TRACE SHOULD NOT have side effects, and
     *   so are inherently idempotent.
     */
    val Idempotent = ByMethod(Set(
      Method.Get,
      Method.Head,
      Method.Put,
      Method.Delete,
      Method.Options,
      Method.Trace
    ))
  }

  object Responses {

    object Failure {
      def unapply(rsp: Response): Boolean =
        rsp.statusCode >= 500 && rsp.statusCode <= 599
    }

    object Retryable {
      // There are porbably some (linkerd-generated) failures that aren't
      // really retryable... For now just check if it's a failure.
      def unapply(rsp: Response): Boolean = Failure.unapply(rsp)
    }
  }

  /**
   * Classifies 5XX responses as failures. If the method is idempotent
   * (as described by RFC2616), it is classified as retryable.
   */
  val RetryableIdempotentFailures: ResponseClassifier =
    ResponseClassifier.named("RetryableIdempotentFailures") {
      case ReqRep(Requests.Idempotent(), Return(Responses.Retryable()) | Throw(NonFatal(_))) =>
        ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses as failures. If the method is a read
   * operation, it is classified as retryable.
   */
  val RetryableReadFailures: ResponseClassifier =
    ResponseClassifier.named("RetryableReadFailures") {
      case ReqRep(Requests.ReadOnly(), Return(Responses.Retryable()) | Throw(NonFatal(_))) =>
        ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses and all exceptions as non-retryable
   * failures.
   */
  val NonRetryableFailures: ResponseClassifier =
    ResponseClassifier.named("NonRetryableFailures") {
      case ReqRep(_, Return(Responses.Failure()) | Throw(NonFatal(_))) =>
        ResponseClass.NonRetryableFailure
    }

}

class RetryableIdempotent5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.RetryableIdempotentFailures
}

class RetryableIdempotent5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableIdempotent5XXConfig]
  override val configId = "retryableIdempotent5XX"
}

object RetryableIdempotent5XXInitializer extends RetryableIdempotent5XXInitializer

class RetryableRead5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.RetryableReadFailures
}

class RetryableRead5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableRead5XXConfig]
  override val configId = "retryableRead5XX"
}

object RetryableRead5XXInitializer extends RetryableRead5XXInitializer

class NonRetryable5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.NonRetryableFailures
}

class NonRetryable5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[NonRetryable5XXConfig]
  override val configId = "nonRetryable5XX"
}

object NonRetryable5XXInitializer extends NonRetryable5XXInitializer
