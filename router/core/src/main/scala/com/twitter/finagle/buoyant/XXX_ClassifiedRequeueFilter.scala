package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service._
import com.twitter.finagle.stats.{Counter, StatsReceiver}
import com.twitter.util._

/*
 * Copied from com.twitter.finagle.service.RequeueFilter
 * [https://github.com/twitter/finagle/pull/504].
 * 
 * XXX remove once the Pull Request is merged & released.
 */
object XXX_ClassifierRequeueFilter {
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module4[param.Stats, Retries.Budget, param.ResponseClassifier, param.HighResTimer, ServiceFactory[Req, Rep]] {
      def role: Stack.Role = Retries.Role

      def description: String =
        "Retries requests, at the service application level, that have been rejected"

      def make(
        statsP: param.Stats,
        budgetP: Retries.Budget,
        classifierP: param.ResponseClassifier,
        timerP: param.HighResTimer,
        next: ServiceFactory[Req, Rep]
      ): ServiceFactory[Req, Rep] = {
        val statsRecv = statsP.statsReceiver
        val scoped = statsRecv.scope("retries")
        val requeues = scoped.counter("requeues")
        val retryBudget = budgetP.retryBudget
        val param.ResponseClassifier(classifier) = classifierP
        val timer = timerP.timer

        val filter = newRequeueFilter[Req, Rep](
          retryBudget,
          budgetP.requeueBackoffs,
          scoped,
          () => next.status == Status.Open,
          MaxRequeuesPerReq,
          timer,
          classifier
        )
        svcFactory(retryBudget, filter, scoped, requeues, next)
      }
    }

  // The upper bound on service acquisition attempts
  private[this] val Effort = 25

  // semi-arbitrary, but we don't want requeues to eat the entire budget
  private[this] val MaxRequeuesPerReq = 0.2

  private[this] def svcFactory[Req, Rep](
    retryBudget: RetryBudget,
    filters: Filter[Req, Rep, Req, Rep],
    statsReceiver: StatsReceiver,
    requeuesCounter: Counter,
    next: ServiceFactory[Req, Rep]
  ): ServiceFactory[Req, Rep] = {
    new ServiceFactoryProxy(next) {
      // We define the gauge inside of the ServiceFactory so that their lifetimes
      // are tied together.
      private[this] val budgetGauge =
        statsReceiver.addGauge("budget") { retryBudget.balance }
      private[this] val notOpenCounter =
        statsReceiver.counter("not_open")

      private[this] val serviceFn: Service[Req, Rep] => Service[Req, Rep] =
        service => filters.andThen(service)

      /**
       * Failures to acquire a service can be thought of as local failures because
       * we're certain that we haven't dispatched a request yet. Thus, this simply
       * tries up to `n` attempts to acquire a service. However, we still only
       * requeue a subset of exceptions (currently only `RetryableWriteExceptions`) as
       * some exceptions to acquire a service are considered fatal.
       */
      private[this] def applySelf(conn: ClientConnection, n: Int): Future[Service[Req, Rep]] =
        self(conn).rescue {
          case e@RetryPolicy.RetryableWriteException(_) if n > 0 =>
            if (status == Status.Open) {
              requeuesCounter.incr()
              applySelf(conn, n - 1)
            } else {
              notOpenCounter.incr()
              Future.exception(e)
            }
        }

      override def apply(conn: ClientConnection): Future[Service[Req, Rep]] =
        applySelf(conn, Effort).map(serviceFn)

      override def close(deadline: Time): Future[Unit] = {
        budgetGauge.remove()
        self.close(deadline)
      }

    }
  }

  def newRequeueFilter[Req, Rep](
    retryBudget: RetryBudget,
    retryBackoffs: Stream[Duration],
    statsReceiver: StatsReceiver,
    canRetry: () => Boolean,
    maxRetriesPerReq: Double,
    timer: Timer,
    classifier: ResponseClassifier = PartialFunction.empty
  ) = new SimpleFilter[Req, Rep] {
    require(
      maxRetriesPerReq >= 0,
      s"maxRetriesPerReq must be non-negative: $maxRetriesPerReq"
    )

    private[this] val requeueCounter = statsReceiver.counter("requeues")
    private[this] val budgetExhaustCounter = statsReceiver.counter("budget_exhausted")
    private[this] val requestLimitCounter = statsReceiver.counter("request_limit")
    private[this] val requeueStat = statsReceiver.stat("requeues_per_request")
    private[this] val canNotRetryCounter = statsReceiver.counter("cannot_retry")

    private[this] def responseFuture(
      attempt: Int,
      t: Try[Rep]
    ): Future[Rep] = {
      requeueStat.add(attempt)
      Future.const(t)
    }

    private[this] def applyService(
      req: Req,
      service: Service[Req, Rep],
      attempt: Int,
      retriesRemaining: Int,
      backoffs: Stream[Duration]
    ): Future[Rep] = {
      service(req).transform { result =>
        classifier.applyOrElse(ReqRep(req, result), ResponseClassifier.Default) match {
          case ResponseClass.RetryableFailure =>
            if (!canRetry()) {
              canNotRetryCounter.incr()
              responseFuture(attempt, result)
            } else if (retriesRemaining > 0 && retryBudget.tryWithdraw()) {
              backoffs match {
                case Duration.Zero #:: rest =>
                  // no delay between retries. Retry immediately.
                  requeueCounter.incr()
                  applyService(req, service, attempt + 1, retriesRemaining - 1, rest)
                case delay #:: rest =>
                  // Delay and then retry.
                  timer.doLater(delay) {
                    requeueCounter.incr()
                    applyService(req, service, attempt + 1, retriesRemaining - 1, rest)
                  }.flatten
                case _ =>
                  // Schedule has run out of entries. Budget is empty.
                  budgetExhaustCounter.incr()
                  responseFuture(attempt, result)
              }
            } else {
              if (retriesRemaining > 0)
                budgetExhaustCounter.incr()
              else
                requestLimitCounter.incr()
              responseFuture(attempt, result)
            }

          case _ =>
            responseFuture(attempt, result)
        }
      }
    }

    def apply(req: Req, service: Service[Req, Rep]): Future[Rep] = {
      retryBudget.deposit()
      val maxRetries = Math.ceil(maxRetriesPerReq * retryBudget.balance).toInt
      applyService(req, service, 0, maxRetries, retryBackoffs)
    }
  }

}
