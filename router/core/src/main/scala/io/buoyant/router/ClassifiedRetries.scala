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
   * @see com.twitter.finagle.service.Backoff
   */
  case class Backoffs(backoff: Stream[Duration])
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
  private class ClassifiedPolicy[Req, Rsp](backoff: Stream[Duration], classifier: ResponseClassifier)
    extends RetryPolicy[(Req, Try[Rsp])] {

    def apply(in: (Req, Try[Rsp])): Option[(Duration, RetryPolicy[(Req, Try[Rsp])])] = {
      val (req, rsp) = in
      rsp match {
        case Throw(f: Failure) if f.isFlagged(Failure.Interrupted) => None
        case _ =>
          classifier.applyOrElse(ReqRep(req, rsp), ClassifiedRetries.Default) match {
            case ResponseClass.RetryableFailure =>
              backoff match {
                case pause #:: rest => Some((pause, new ClassifiedPolicy(rest, classifier)))
                case _ => None
              }
            case _ => None
          }
      }
    }
  }

  /**
   * A stack module that installs a RetryFilter that uses the stack's
   * ResponseClassifier.
   */
  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module5[Backoffs, param.ResponseClassifier, Retries.Budget, param.HighResTimer, param.Stats, ServiceFactory[Req, Rsp]] {
      val role = ClassifiedRetries.role
      val description = "Retries requests that are classified to be retryable"
      def make(
        _backoffs: Backoffs,
        _classifier: param.ResponseClassifier,
        _budget: Retries.Budget,
        _timer: param.HighResTimer,
        _stats: param.Stats,
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = {
        val Backoffs(backoff) = _backoffs
        val param.ResponseClassifier(classifier) = _classifier
        val Retries.Budget(budget, _) = _budget
        val param.HighResTimer(timer) = _timer
        val param.Stats(stats) = _stats
        val policy = new ClassifiedPolicy[Req, Rsp](backoff, classifier)
        val filter = new RetryFilter[Req, Rsp](policy, timer, stats, budget)
        filter andThen next
      }
    }
}
