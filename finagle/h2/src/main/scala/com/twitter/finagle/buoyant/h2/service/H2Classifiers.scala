package com.twitter.finagle.buoyant.h2.service

import com.twitter.finagle.buoyant.h2._
import com.twitter.finagle.buoyant.h2.service.H2Classifiers.AllSuccessful.responseClassifier
import com.twitter.finagle.service.{ResponseClass, RetryPolicy}
import com.twitter.util.{Return, Throw, Try}

object H2Classifiers {

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

    /** Matches all Http Methods **/
    val All: ByMethod = Idempotent.withMethods(Set(
      Method.Post,
      Method.Patch
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
    private[H2Classifiers] val retryableThrow: PartialFunction[Try[Nothing], Boolean] =
      RetryPolicy.TimeoutAndWriteExceptionsOnly
        .orElse(RetryPolicy.ChannelClosedExceptionsOnly)
        .orElse { case _ => false }

    def unapply(rsp: Try[Response]): Boolean = rsp match {
      case Return(Responses.Failure.Retryable()) => true
      case Throw(e) => retryableThrow(Throw(e))
      case _ => false
    }
  }

  /**
   * Classifies 5XX responses as failures. If the method is idempotent
   * (as described by RFC2616), it is classified as retryable.
   */
  case object RetryableIdempotentFailures extends H2Classifier {
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(Requests.Idempotent(), RetryableResult()) => ResponseClass.RetryableFailure
      case H2ReqRep(_, Return(Responses.Failure())) => ResponseClass.NonRetryableFailure
      case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    }
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(Requests.Idempotent(), Throw(e)) if RetryableResult.retryableThrow(Throw(e)) =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(Requests.Idempotent(), Return((_, Some(Throw(e))))) if RetryableResult.retryableThrow(Throw(e)) =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(_, Throw(_)) =>
        ResponseClass.NonRetryableFailure
      case H2ReqRepFrame(_, Return((_, Some(Throw(_))))) =>
        ResponseClass.NonRetryableFailure
      case _ =>
        ResponseClass.Success
    }
  }

  /**
   * Classifies 5XX responses as failures. All Http Methods
   * are classified as retryable.
   */
  case object RetryableAllFailures extends H2Classifier {
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(Requests.All(), RetryableResult()) => ResponseClass.RetryableFailure
      case H2ReqRep(_, Return(Responses.Failure())) => ResponseClass.NonRetryableFailure
      case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    }
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(Requests.All(), Throw(e)) if RetryableResult.retryableThrow(Throw(e)) =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(Requests.All(), Return((_, Some(Throw(e))))) if RetryableResult.retryableThrow(Throw(e)) =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(_, Throw(_)) =>
        ResponseClass.NonRetryableFailure
      case H2ReqRepFrame(_, Return((_, Some(Throw(_))))) =>
        ResponseClass.NonRetryableFailure
      case _ =>
        ResponseClass.Success
    }
  }

  /**
   * Classifies 5XX responses as failures. If the method is a read
   * operation, it is classified as retryable.
   */
  case object RetryableReadFailures extends H2Classifier {
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(Requests.ReadOnly(), RetryableResult()) => ResponseClass.RetryableFailure
      case H2ReqRep(_, Return(Responses.Failure())) => ResponseClass.NonRetryableFailure
      case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    }
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(Requests.ReadOnly(), Throw(e)) if RetryableResult.retryableThrow(Throw(e)) =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(Requests.ReadOnly(), Return((_, Some(Throw(e))))) if RetryableResult.retryableThrow(Throw(e)) =>
        ResponseClass.RetryableFailure
      case H2ReqRepFrame(_, Throw(_)) =>
        ResponseClass.NonRetryableFailure
      case H2ReqRepFrame(_, Return((_, Some(Throw(_))))) =>
        ResponseClass.NonRetryableFailure
      case _ =>
        ResponseClass.Success
    }
  }

  /**
   * Classifies 5XX responses and all exceptions as non-retryable
   * failures.
   */
  case object NonRetryableServerFailures extends H2Classifier {
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(_, Throw(_) | Return((Responses.Failure()))) => ResponseClass.NonRetryableFailure
    }
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_,
        Throw(_)
        | Return((Responses.Failure(), _))
        | Return((_, Some(Throw(_))))
        ) => ResponseClass.NonRetryableFailure
      case _ => ResponseClass.Success
    }
  }

  case object AllSuccessful extends H2Classifier {
    override val responseClassifier: PartialFunction[H2ReqRep, ResponseClass] = {
      case H2ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
    }
    override val streamClassifier: PartialFunction[H2ReqRepFrame, ResponseClass] = {
      case H2ReqRepFrame(_, Throw(_)) => ResponseClass.NonRetryableFailure
      case H2ReqRepFrame(_, Return((_, Some(Throw(_))))) => ResponseClass.NonRetryableFailure
      case _ => ResponseClass.Success
    }
  }

  val Default: H2Classifier = NonRetryableServerFailures

}
