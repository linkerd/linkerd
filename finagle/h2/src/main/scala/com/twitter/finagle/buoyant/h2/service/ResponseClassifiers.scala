package com.twitter.finagle.buoyant.h2.service

import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.service.H2ReqRep.RepAndFrame
import com.twitter.finagle.service.{ResponseClass, RetryPolicy}
import com.twitter.util.{Return, Throw, Try}

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
    val Idempotent: ByMethod = ReadOnly.withMethods(Set(
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
    object Success {
      def unapply(rsp: Response): Boolean = rsp.status match {
        case Status.ServerError(_) => false
        case _ => true
      }
    }
  }

  object RetryableResult {
    private[this] val retryableThrow: PartialFunction[Try[Nothing], Boolean] =
      RetryPolicy.TimeoutAndWriteExceptionsOnly
        .orElse(RetryPolicy.ChannelClosedExceptionsOnly)
        .orElse { case _ => false }

    def unapply(rsp: Try[RepAndFrame]): Boolean = rsp match {
      case Return((Responses.Failure.Retryable(), None | Some(Return(_)))) => true
      case Return((Responses.Success() | Responses.Failure.Retryable(),
        Some(Throw(e)))) => retryableThrow(Throw(e))
      case Throw(e) => retryableThrow(Throw(e))
      case _ => false
    }
  }

  /**
   * Create a [[ResponseClassifier]] with the given name for its `toString`.
   */
  def named(name: String)(underlying: ResponseClassifier): ResponseClassifier =
    new ResponseClassifier {
      def isDefinedAt(reqRep: H2ReqRep): Boolean = underlying.isDefinedAt(reqRep)
      def apply(reqRep: H2ReqRep): ResponseClass = underlying(reqRep)
      override def toString: String = name
    }

  /**
   * Classifies 5XX responses as failures. If the method is idempotent
   * (as described by RFC2616), it is classified as retryable.
   */
  val RetryableIdempotentFailures: ResponseClassifier =
    named("RetryableIdempotentFailures") {
      case H2ReqRep(Requests.Idempotent(), RetryableResult()) => ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses as failures. If the method is a read
   * operation, it is classified as retryable.
   */
  val RetryableReadFailures: ResponseClassifier =
    named("RetryableReadFailures") {
      case H2ReqRep(Requests.ReadOnly(), RetryableResult()) => ResponseClass.RetryableFailure
    }

  /**
   * Classifies 5XX responses and all exceptions as non-retryable
   * failures.
   */
  val NonRetryableServerFailures: ResponseClassifier =
    named(s"NonRetryableServerFailures") {
      case H2ReqRep(_,
        Throw(_) | Return((Responses.Failure(), _))
        | Return((_, Some(Throw(_))))
        ) => ResponseClass.NonRetryableFailure
    }

  // TODO allow fully-buffered streams to be retried.
  def NonRetryableStream(classifier: ResponseClassifier): ResponseClassifier =
    named(s"NonRetryableStream") {
      case rr@H2ReqRep(req: Request, _) if classifier.isDefinedAt(rr) =>
        classifier(rr) match {
          case ResponseClass.RetryableFailure if req.stream.nonEmpty =>
            ResponseClass.NonRetryableFailure

          case rc => rc
        }
    }

  /**
   * a simple [[ResponseClassifier]] that classifies responses as failures
   * if an exception was [[com.twitter.util.Throw]]n
   */
  val ExceptionsAsFailures: ResponseClassifier =
    named("ExceptionsAsFailures") {
      case H2ReqRep(_, Throw(_)) | H2ReqRep(_, Return((_, Some(Throw(_))))) =>
        ResponseClass.NonRetryableFailure
    }

  val AssumeSuccess: ResponseClassifier = {
    case _ => ResponseClass.Success
  }

  /**
   * an [[ResponseClassifier]] that first tries to classify [[ExceptionsAsFailures]],
   * then tries to classify [[NonRetryableServerFailures]] and finally
   * [[AssumeSuccess assumes success]] for unclassified responses
   */
  val Default: ResponseClassifier =
    named("DefaultH2ResponseClassifier") {
      ExceptionsAsFailures orElse NonRetryableServerFailures orElse AssumeSuccess
    }

}
