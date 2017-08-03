package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.service.ResponseClassifier
import com.twitter.finagle.service.RetryPolicy.{TimeoutAndWriteExceptionsOnly, ChannelClosedExceptionsOnly}
import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.finagle.http.service.HttpResponseClassifier
import com.twitter.finagle.service.{ResponseClass, ReqRep, ResponseClassifier}
import com.twitter.util.{NonFatal, Return, Throw, Try}
import io.buoyant.config.ConfigInitializer
import io.buoyant.linkerd.{ResponseClassifierConfig, ResponseClassifierInitializer}
import io.buoyant.router.ClassifiedRetries

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

  /**
   * Matches badly-framed responses
   */
  val FramingExceptionsOnly: PartialFunction[Try[Nothing], Boolean] = {
    case Throw(FramingFilter.FramingException(_)) => true
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

  object RetryableResult {
    private[this] val retryableThrow: PartialFunction[Try[Nothing], Boolean] =
      TimeoutAndWriteExceptionsOnly.orElse(ChannelClosedExceptionsOnly)
        .orElse(FramingExceptionsOnly)
        .orElse { case _ => false }

    def unapply(rsp: Try[Any]): Boolean = rsp match {
      case Return(Responses.Failure.Retryable()) => true
      case Throw(e) => retryableThrow(Throw(e))
      case _ => false
    }
  }

  /**
   * Classifies 5XX responses as failures. If the method is idempotent
   * (as described by RFC2616), it is classified as retryable.
   */
  val RetryableIdempotentFailures: ResponseClassifier =
    ResponseClassifier.named("RetryableIdempotentFailures") {
      case ReqRep(Requests.Idempotent(), RetryableResult()) => ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses as failures. If the method is a read
   * operation, it is classified as retryable.
   */
  val RetryableReadFailures: ResponseClassifier =
    ResponseClassifier.named("RetryableReadFailures") {
      case ReqRep(Requests.ReadOnly(), RetryableResult()) => ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses and all exceptions as non-retryable
   * failures.
   */
  val NonRetryableServerFailures: ResponseClassifier =
    HttpResponseClassifier.ServerErrorsAsFailures

  def NonRetryableChunked(classifier: ResponseClassifier): ResponseClassifier =
    ResponseClassifier.named(s"NonRetryableChunked[$classifier]") {
      case rr@ReqRep(req, _) if classifier.isDefinedAt(rr) =>
        (req, classifier(rr)) match {
          case (req: Request, ResponseClass.RetryableFailure) if req.isChunked =>
            ResponseClass.NonRetryableFailure
          case (_, rc) => rc
        }
    }

  def HeaderRetryable(classifier: ResponseClassifier): ResponseClassifier =
    ResponseClassifier.named(s"HeaderRetryable[$classifier]") {
      case rr if classifier.isDefinedAt(rr) =>
        val rc = classifier(rr)
        if (rc == ResponseClass.NonRetryableFailure) {
          rr match {
            case ReqRep(req, Return(rsp: Response)) if Headers.Retryable.get(rsp.headerMap) =>
              ResponseClass.RetryableFailure
            case _ => rc
          }
        } else {
          rc
        }
    }
}

class RetryableIdempotent5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.RetryableIdempotentFailures
}

class RetryableIdempotent5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableIdempotent5XXConfig]
  override val configId = "io.l5d.http.retryableIdempotent5XX"
}

object RetryableIdempotent5XXInitializer extends RetryableIdempotent5XXInitializer

class RetryableRead5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.RetryableReadFailures
}

class RetryableRead5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[RetryableRead5XXConfig]
  override val configId = "io.l5d.http.retryableRead5XX"
}

object RetryableRead5XXInitializer extends RetryableRead5XXInitializer

class NonRetryable5XXConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ResponseClassifiers.NonRetryableServerFailures
}

class NonRetryable5XXInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[NonRetryable5XXConfig]
  override val configId = "io.l5d.http.nonRetryable5XX"
}

object NonRetryable5XXInitializer extends NonRetryable5XXInitializer

class AllSuccessfulConfig extends ResponseClassifierConfig {
  def mk: ResponseClassifier = ClassifiedRetries.Default
}

class AllSuccessfulInitializer extends ResponseClassifierInitializer {
  val configClass = classOf[AllSuccessfulConfig]
  override val configId = "io.l5d.http.allSuccessful"
}

object AllSuccessfulInitializer extends AllSuccessfulInitializer
