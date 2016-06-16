package com.twitter.finagle.buoyant

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.service.{DeadlineFilter, RetryBudget}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.util.{Duration, Future, Stopwatch, Time, Timer}

object DeadlineRejectFilter {

  val role = new Stack.Role("DeadlineRejectFilter")
  val statsRole = DeadlineFilter.role

  val RejectAnnotation: String = "l5d.deadline.reject"
  val TimeoutAnnotation: String = "l5d.deadline.timeout"

  case class Enabled(enabled: Boolean)
  implicit object Enabled extends Stack.Param[Enabled] {
    val default = Enabled(true)
  }

  case class Budget(budget: RetryBudget)
  implicit object Budget extends Stack.Param[Budget] {
    def default = Budget(RetryBudget(10.seconds, 0, 0.2, Stopwatch.systemMillis))
  }

  case class Tolerance(tolerance: Duration)
  implicit object Tolerance extends Stack.Param[Tolerance] {
    val default = Tolerance(170.milliseconds) // max empirically measured clock ske
  }

  /**
   * Creates a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.service.DeadlineRejectFilter]].
   */
  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module5[Enabled, Budget, Tolerance, param.Stats, param.Timer, ServiceFactory[Req, Rsp]] {
      val role = DeadlineRejectFilter.role
      val description = "Fails requests that have surpassed their deadline"

      def make(
        _enabled: Enabled,
        _budget: Budget,
        _tolerance: Tolerance,
        _stats: param.Stats,
        _timer: param.Timer,
        next: ServiceFactory[Req, Rsp]
      ) = _enabled match {
        case Enabled(true) =>
          val Budget(budget) = _budget
          val Tolerance(tolerance) = _tolerance
          val param.Stats(stats) = _stats
          val param.Timer(timer) = _timer
          val scopedStats = stats.scope("admission_control", "deadline")
          val filter = new DeadlineRejectFilter[Req, Rsp](tolerance, budget, scopedStats, timer)
          filter.andThen(next)

        case _ => next
      }
    }

  private def message(d: Deadline) =
    s"exceeded request deadline of ${d.deadline - d.timestamp} by ${d.remaining}. " +
      s"Deadline expired at ${d.deadline} and now it is ${Time.now}."

  private def reject[T](d: Deadline): Future[T] =
    Future.exception(Failure.rejected(message(d)))
}

/**
 * A [[com.twitter.finagle.Filter]] that records the number of requests
 * with exceeded deadlines, the remaining deadline budget, and the
 * transit latency of requests.
 *
 * @param statsReceiver for stats reporting, typically scoped to
 * ".../admission_control/deadline/"
 *
 */
private[finagle] class DeadlineRejectFilter[Req, Rsp](
  tolerance: Duration,
  budget: RetryBudget,
  statsReceiver: StatsReceiver,
  timer: Timer
) extends SimpleFilter[Req, Rsp] {

  private[this] val rejectedCounter = statsReceiver.counter("rejected")
  private[this] val beyondToleranceCounter =
    statsReceiver.counter("beyond_tolerance")
  private[this] val budgetExhaustedCounter =
    statsReceiver.counter("budget_exhausted")
  private[this] val canceledCounter =
    statsReceiver.counter("canceled")

  def apply(request: Req, service: Service[Req, Rsp]): Future[Rsp] = {
    budget.deposit()
    Deadline.current match {
      case Some(d) =>
        val remaining = d.remaining
        if (remaining <= Duration.Zero) { // expired
          if (-remaining <= tolerance) { // not toooo expired
            if (budget.tryWithdraw()) { // can reject
              rejectedCounter.incr()
              Trace.record(DeadlineRejectFilter.RejectAnnotation)
              DeadlineRejectFilter.reject(d)
            } else { // too many rejections
              budgetExhaustedCounter.incr()
              service(request)
            }
          } else { // too expired to be a real deadline
            beyondToleranceCounter.incr()
            service(request)
          }
        } else { // not expired
          val rsp = service(request)
          rsp.within(timer, remaining).rescue {
            case exc: java.util.concurrent.TimeoutException =>
              if (budget.tryWithdraw()) { // timed out and can cancel
                rsp.raise(exc)
                canceledCounter.incr()
                Trace.record(DeadlineRejectFilter.TimeoutAnnotation, remaining)
                Future.exception(new GlobalRequestTimeoutException(remaining))
              } else { // can't cancel
                budgetExhaustedCounter.incr()
                rsp
              }
          }
        }

      case None => service(request)
    }
  }

}
