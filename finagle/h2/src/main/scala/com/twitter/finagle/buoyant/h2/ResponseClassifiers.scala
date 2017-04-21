package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.service._
import com.twitter.util.{NonFatal, Return, Throw, Try}

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

  object RetryableResult {
    private[this] val retryableThrow: PartialFunction[Try[Nothing], Boolean] =
      RetryPolicy.TimeoutAndWriteExceptionsOnly
        .orElse(RetryPolicy.ChannelClosedExceptionsOnly)
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
    ResponseClassifier.named(s"NonRetryableServerFailures") {
      case ReqRep(_, Throw(_) | Return(Responses.Failure())) => ResponseClass.NonRetryableFailure
    }

  // TODO allow fully-buffered streams to be retried.
  def NonRetryableStream(classifier: ResponseClassifier): ResponseClassifier =
    ResponseClassifier.named(s"NonRetryableStream") {
      case rr@ReqRep(req: Request, _) if classifier.isDefinedAt(rr) =>
        classifier(rr) match {
          case ResponseClass.RetryableFailure if req.stream.nonEmpty =>
            ResponseClass.NonRetryableFailure

          case rc => rc
        }
    }
}