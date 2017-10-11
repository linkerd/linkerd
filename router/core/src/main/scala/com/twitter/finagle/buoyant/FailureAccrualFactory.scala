package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack.{Params, Role}
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.liveness.{FailureAccrualPolicy, FailureAccrualFactory => FFailureAccrualFactory}
import com.twitter.finagle.liveness.FailureAccrualFactory.Param
import com.twitter.finagle.service.{ReqRep, ResponseClass, ResponseClassifier}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}
import com.twitter.logging.Level
import com.twitter.util.Timer
import io.buoyant.router.context.ResponseClassifierCtx

/**
 * A replacement for Finagle's FailureAccrualFactory that reads the respones classifier from the
 * request local context.
 */
object FailureAccrualFactory {

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.ModuleParams[ServiceFactory[Req, Rep]] {
      val role: Role = FFailureAccrualFactory.role
      val description: String = "Backoff from hosts that we cannot successfully make requests to using local response classifier"
      override def parameters: Seq[Stack.Param[_]] = Seq(
        implicitly[Stack.Param[param.Stats]],
        implicitly[Stack.Param[Param]],
        implicitly[Stack.Param[param.Timer]],
        implicitly[Stack.Param[param.Label]],
        implicitly[Stack.Param[param.Logger]],
        implicitly[Stack.Param[Transporter.EndpointAddr]]
      )

      def make(params: Params, next: ServiceFactory[Req, Rep]): ServiceFactory[Req, Rep] = {
        params[Param] match {
          case Param.Configured(policy) =>
            val timer = params[param.Timer].timer
            val statsReceiver = params[param.Stats].statsReceiver

            // extract some info useful for logging
            val logger = params[param.Logger].log
            val endpoint = params[Transporter.EndpointAddr].addr
            val label = params[param.Label].label

            new FailureAccrualFactory[Req, Rep](
              underlying = next,
              policy = policy(),
              timer = timer,
              statsReceiver = statsReceiver.scope("failure_accrual")
            ) {
              override def didMarkDead(): Unit = {
                logger.log(
                  Level.INFO,
                  s"""FailureAccrualFactory marking connection to "$label" as dead. """ +
                    s"""Remote Address: $endpoint"""
                )
                super.didMarkDead()
              }
            }

          case Param.Replaced(f) =>
            f(params[param.Timer].timer).andThen(next)

          case Param.Disabled =>
            next
        }
      }
    }

  val NullResponseClassifier: ResponseClassifier = {
    case _ => throw new NotImplementedError()
  }
}

class FailureAccrualFactory[Req, Rep](
  underlying: ServiceFactory[Req, Rep],
  policy: FailureAccrualPolicy,
  timer: Timer,
  statsReceiver: StatsReceiver
) extends FFailureAccrualFactory[Req, Rep](
  underlying, policy, FailureAccrualFactory.NullResponseClassifier, timer, statsReceiver
) {
  override protected def isSuccess(reqRep: ReqRep): Boolean = {
    val param.ResponseClassifier(classifier) =
      ResponseClassifierCtx.current.getOrElse(param.ResponseClassifier.param.default)
    classifier.applyOrElse(reqRep, ResponseClassifier.Default) match {
      case ResponseClass.Successful(_) => true
      case ResponseClass.Failed(_) => false
    }
  }
}
