package com.twitter.finagle.buoyant

import com.twitter.conversions.time._
import com.twitter.finagle.service.{RetryBudget, RetryPolicy}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.{Filter, Service}
import com.twitter.util._

/**
 * Cribbed from:
 * https://github.com/twitter/finagle/blob/develop/finagle-core/src/main/scala/com/twitter/finagle/service/RetryFilter.scala
 *
 * Copied here and modified in order to change the type of stats exported by the filter.
 */
class RetryFilter[Req, Rep](
  retryPolicy: RetryPolicy[(Req, Try[Rep])],
  timer: Timer,
  statsReceiver: StatsReceiver,
  retryBudget: RetryBudget
)
  extends Filter[Req, Rep, Req, Rep] {

  def this(
    retryPolicy: RetryPolicy[(Req, Try[Rep])],
    timer: Timer,
    statsReceiver: StatsReceiver
  ) = this(
    retryPolicy,
    timer,
    statsReceiver,
    RetryBudget()
  )

  private[this] val retriesStat = statsReceiver.counter("retries")

  private[this] val budgetExhausted =
    statsReceiver.scope("retries").counter("budget_exhausted")

  @inline
  private[this] def schedule(d: Duration)(f: => Future[Rep]) = {
    if (d > 0.seconds) {
      val promise = new Promise[Rep]
      timer.schedule(Time.now + d) {
        promise.become(f)
      }
      promise
    } else f
  }

  private[this] def dispatch(
    req: Req,
    service: Service[Req, Rep],
    policy: RetryPolicy[(Req, Try[Rep])],
    count: Int = 0
  ): Future[Rep] = {
    val svcRep = service(req)
    svcRep.transform { rep =>
      policy((req, rep)) match {
        case Some((howlong, nextPolicy)) =>
          if (retryBudget.tryWithdraw()) {
            schedule(howlong) {
              Trace.record("finagle.retry")
              dispatch(req, service, nextPolicy, count + 1)
            }
          } else {
            budgetExhausted.incr()
            retriesStat.incr(count)
            svcRep
          }
        case None =>
          retriesStat.incr(count)
          svcRep
      }
    }
  }

  def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
    retryBudget.deposit()
    dispatch(request, service, retryPolicy)
  }
}
