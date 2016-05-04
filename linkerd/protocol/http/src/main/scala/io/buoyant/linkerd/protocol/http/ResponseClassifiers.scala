package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.finagle.http.service.HttpResponseClassifier
import com.twitter.finagle.service.{ResponseClass, ReqRep, ResponseClassifier}
import com.twitter.util.{NonFatal, Return, Throw}
import io.buoyant.config.ConfigInitializer
import io.buoyant.linkerd.{ResponseClassifierConfig, ResponseClassifierInitializer}

object ResponseClassifiers {

  object Requests {

    case class ByMethod(methods: Set[Method]) {
      def withMethods(other: Set[Method]): ByMethod = copy(methods ++ other)
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
    val Idempotent = ReadOnly.withMethods(Set(
      Method.Put,
      Method.Delete
    ))
  }

  object Responses {

    object Failure {
      def unapply(rsp: Response): Boolean = rsp.status match {
        case Status.ServerError(_) => true
        case _ => false
      }

      // There are probably some (linkerd-generated) failures that aren't
      // really retryable... For now just check if it's a failure.
      object Retryable {
        def unapply(rsp: Response): Boolean = Failure.unapply(rsp)
      }
    }
  }

  /**
   * Classifies 5XX responses as failures. If the method is idempotent
   * (as described by RFC2616), it is classified as retryable.
   */
  val RetryableIdempotentFailures: ResponseClassifier =
    ResponseClassifier.named("RetryableIdempotentFailures") {
      case ReqRep(Requests.Idempotent(), Return(Responses.Failure.Retryable()) | Throw(NonFatal(_))) =>
        ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses as failures. If the method is a read
   * operation, it is classified as retryable.
   */
  val RetryableReadFailures: ResponseClassifier =
    ResponseClassifier.named("RetryableReadFailures") {
      case ReqRep(Requests.ReadOnly(), Return(Responses.Failure.Retryable()) | Throw(NonFatal(_))) =>
        ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses and all exceptions as non-retryable
   * failures.
   */
  val NonRetryableServerFailures: ResponseClassifier =
    HttpResponseClassifier.ServerErrorsAsFailures
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
  def mk: ResponseClassifier = ResponseClassifiers.NonRetryableServerFailures
}

class NonRetryable5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[NonRetryable5XXConfig]
  override val configId = "nonRetryable5XX"
}

object NonRetryable5XXInitializer extends NonRetryable5XXInitializer
