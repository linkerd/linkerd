package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.RetryFilter
import com.twitter.finagle.service.{RetryFilter => _, _}
import com.twitter.util._

object ClassifiedRetries {
  val role = Stack.Role("ClassifiedRetries")

  /**
   * A backoff policy to be used when retrying application-level
   * failures.
   *
   * @see com.twitter.finagle.Backoff
   */
  case class Backoffs(backoff: Backoff)
  implicit object Backoffs extends Stack.Param[Backoffs] {
    val default = Backoffs(Backoff.const(Duration.Zero))
  }

  // Note that we don't retry RetryableWriteExceptions here.  We are assuming
  // that those requeues happen in the requeue filter and if a requeuable
  // exception has made it here, the requeue budget must be exhausted or the
  // requeue limit must have been reached.
  val Default: ResponseClassifier = ResponseClassifier.named("DefaultResponseClassifier") {
    case ReqRep(_, Return(_)) => ResponseClass.Success
    case ReqRep(_, Throw(_)) => ResponseClass.NonRetryableFailure
  }

  /**
   * A replacement for ResponseClassifier's orElse that gives a nice toString.
   */
  def orElse(a: ResponseClassifier, b: ResponseClassifier): ResponseClassifier = new ResponseClassifier {
    private[this] val underlying = a orElse b
    def isDefinedAt(reqRep: ReqRep): Boolean = underlying.isDefinedAt(reqRep)
    def apply(reqRep: ReqRep): ResponseClass = underlying(reqRep)
    override def toString: String = s"$a orElse $b"
  }

  /**
   * A RetryPolicy that uses a ResponseClassifier.
   */
  private class ClassifiedPolicy[Req, Rsp](backoff: Backoff, classifier: ResponseClassifier)
    extends RetryPolicy[(Req, Try[Rsp])] {

    def apply(in: (Req, Try[Rsp])): Option[(Duration, RetryPolicy[(Req, Try[Rsp])])] = {
      val (req, rsp) = in
      rsp match {
        case Throw(f: Failure) if f.isFlagged(FailureFlags.Interrupted) => None
        case _ =>
          classifier.applyOrElse(ReqRep(req, rsp), ClassifiedRetries.Default) match {
            case ResponseClass.RetryableFailure =>
              if (backoff.isExhausted) {
                None
              } else {
                Some((backoff.duration, new ClassifiedPolicy(backoff.next, classifier)))
              }
            case _ => None
          }
      }
    }
  }

  case class ResponseDiscarder[Rsp](discard: Rsp => Unit)
  object ResponseDiscarder {
    private val _param = new Stack.Param[ResponseDiscarder[Any]] {
      val default = ResponseDiscarder(RetryFilter.noopDiscard[Any])
    }
    // Ah... the illusion of type safety
    def param[Rsp] = _param.asInstanceOf[Stack.Param[ResponseDiscarder[Rsp]]]
  }

  /**
   * A stack module that installs a RetryFilter that uses the stack's
   * ResponseClassifier.
   */
  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] = {
    implicit val discarderParam = ResponseDiscarder.param[Rsp]
    new Stack.Module6[Backoffs, param.ResponseClassifier, Retries.Budget, param.HighResTimer, param.Stats, ResponseDiscarder[Rsp], ServiceFactory[Req, Rsp]] {
      val role = ClassifiedRetries.role
      val description = "Retries requests that are classified to be retryable"
      def make(
        _backoffs: Backoffs,
        _classifier: param.ResponseClassifier,
        _budget: Retries.Budget,
        _timer: param.HighResTimer,
        _stats: param.Stats,
        _discard: ResponseDiscarder[Rsp],
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = {
        val Backoffs(backoff) = _backoffs
        val param.ResponseClassifier(classifier) = _classifier
        val Retries.Budget(budget, _) = _budget
        val param.HighResTimer(timer) = _timer
        val param.Stats(stats) = _stats
        val ResponseDiscarder(discard) = _discard
        val policy = new ClassifiedPolicy[Req, Rsp](backoff, classifier)
        val filter = new RetryFilter[Req, Rsp](policy, timer, stats, budget, discard)
        filter andThen next
      }
    }
  }
}
