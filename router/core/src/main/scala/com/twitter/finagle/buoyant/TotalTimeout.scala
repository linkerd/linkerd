package com.twitter.finagle.buoyant

import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle._
import com.twitter.util.{Duration, Timer}

/**
 * This stack module is intended to enforce a total timeout on a request
 * including all retries.  To do this it must be installed above all retry
 * filters in the stack.
 */
object TotalTimeout {

  case class Param(timeout: Duration)
  object Param {
    implicit val param: Stack.Param[Param] =
      Stack.Param(Param(Duration.Top))
  }

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module2[Param, param.Timer, ServiceFactory[Req, Rep]] {
      val role: Stack.Role = TimeoutFilter.totalTimeoutRole
      val description: String = "Timeout for total request including retries"

      def make(
        timeoutP: Param,
        timerP: param.Timer,
        next: ServiceFactory[Req, Rep]
      ): ServiceFactory[Req, Rep] = {
        val timeout = timeoutP.timeout
        if (!timeout.isFinite || timeout <= Duration.Zero) next
        else {
          val filter = new TimeoutFilter[Req, Rep](
            () => timeout,
            timeout => new GlobalRequestTimeoutException(timeout),
            timerP.timer
          )
          filter.andThen(next)
        }
      }
    }
}