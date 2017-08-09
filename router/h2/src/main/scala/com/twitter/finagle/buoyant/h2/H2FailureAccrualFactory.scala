package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Stack.{Params, Role}
import com.twitter.finagle.buoyant.h2.service.{H2Classifier, H2ReqRep, H2ReqRepFrame}
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.liveness.{FailureAccrualFactory, FailureAccrualPolicy}
import com.twitter.finagle.service.ResponseClass
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Status => FStatus, param => fParam, _}
import com.twitter.logging.Level
import com.twitter.util._
import io.buoyant.router.context.h2.H2ClassifierCtx

/* Modified from https://github.com/twitter/finagle/blob/develop/finagle-core/src/main/scala/com/twitter/finagle/liveness/FailureAccrualFactory.scala */
object H2FailureAccrualFactory {

  val role = FailureAccrualFactory.role

  def module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.ModuleParams[ServiceFactory[Request, Response]] {
      val role: Role = H2FailureAccrualFactory.role
      val description: String = "Backoff from hosts that we cannot successfully make requests to"
      override def parameters: Seq[Stack.Param[_]] = Seq(
        implicitly[Stack.Param[fParam.Stats]],
        implicitly[Stack.Param[FailureAccrualFactory.Param]],
        implicitly[Stack.Param[fParam.Timer]],
        implicitly[Stack.Param[fParam.Label]],
        implicitly[Stack.Param[fParam.Logger]],
        implicitly[Stack.Param[Transporter.EndpointAddr]]
      )

      def make(params: Params, next: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] = {
        params[FailureAccrualFactory.Param] match {
          case FailureAccrualFactory.Param.Configured(policy) =>
            val timer = params[fParam.Timer].timer
            val statsReceiver = params[fParam.Stats].statsReceiver

            // extract some info useful for logging
            val logger = params[fParam.Logger].log
            val endpoint = params[Transporter.EndpointAddr].addr
            val label = params[fParam.Label].label

            new H2FailureAccrualFactory(
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

          case FailureAccrualFactory.Param.Replaced(f) =>
            f(params[fParam.Timer].timer).andThen(next)

          case FailureAccrualFactory.Param.Disabled =>
            next
        }
      }
    }
}

/**
 * A modified version of com.twitter.finagle.liveness.FailureAccrualFactory for H2.
 * This uses a H2Classifier to inform failure accrual at either response time, or when the stream
 * is complete.
 */
class H2FailureAccrualFactory(
  underlying: ServiceFactory[Request, Response],
  policy: FailureAccrualPolicy,
  timer: Timer,
  statsReceiver: StatsReceiver
)
  extends ServiceFactory[Request, Response] { self =>
  import FailureAccrualFactory._

  // writes to `state` and `reviveTimerTask` are synchronized on `self`
  @volatile private[this] var state: State = Alive
  private[this] var reviveTimerTask: Option[TimerTask] = None

  private[this] val removalCounter = statsReceiver.counter("removals")
  private[this] val revivalCounter = statsReceiver.counter("revivals")
  private[this] val probesCounter = statsReceiver.counter("probes")
  private[this] val removedForCounter = statsReceiver.counter("removed_for_ms")

  private[this] def didFail() = self.synchronized {
    state match {
      case Alive | ProbeClosed =>
        policy.markDeadOnFailure() match {
          case Some(duration) => markDeadFor(duration)
          case None if state == ProbeClosed =>
            // The probe request failed, but the policy tells us that we
            // should not mark dead. We probe again in an attempt to
            // resolve this ambiguity, but we could also mark dead for a
            // fixed period of time, or even mark alive.
            startProbing()
          case None =>
        }
      case _ =>
    }
  }

  private[this] val onServiceAcquisitionFailure: Throwable => Unit = self.synchronized { _ =>
    stopProbing()
    didFail()
  }

  protected def didSucceed(): Unit = self.synchronized {
    // Only count revivals when the probe succeeds.
    state match {
      case ProbeClosed =>
        revivalCounter.incr()
        policy.revived()
        state = Alive
      case _ =>
    }
    policy.recordSuccess()
  }

  private[this] def markDeadFor(duration: Duration) = self.synchronized {
    // In order to have symmetry with the revival counter, don't count removals
    // when probing fails.
    if (state == Alive) removalCounter.incr()

    state = Dead

    val timerTask = timer.schedule(duration.fromNow) { startProbing() }
    reviveTimerTask = Some(timerTask)

    removedForCounter.incr(duration.inMilliseconds.toInt)

    didMarkDead()
  }

  /**
   * Called by FailureAccrualFactory after marking an endpoint dead. Override
   * this method to perform additional actions.
   */
  protected def didMarkDead() = {}

  /**
   * Enter 'Probing' state.
   * The service must satisfy one request before accepting more.
   */
  protected def startProbing() = self.synchronized {
    state = ProbeOpen
    cancelReviveTimerTask()
  }

  /**
   * Exit 'Probing' state (if necessary)
   *
   * The result of the subsequent request will determine whether the factory transitions to
   * Alive (successful) or Dead (unsuccessful).
   */
  private[this] def stopProbing() = self.synchronized {
    state match {
      case ProbeOpen =>
        probesCounter.incr()
        state = ProbeClosed
      case _ =>
    }
  }

  def apply(conn: ClientConnection): Future[Service[Request, Response]] = {
    underlying(conn).map { service =>
      // N.B. the reason we can't simply filter the service factory is so that
      // we can override the session status to reflect the broader endpoint status.
      new FailureAccrualService(service)
    }.onFailure(onServiceAcquisitionFailure)
  }

  private[this] class FailureAccrualService(
    underlying: Service[Request, Response]
  ) extends Service[Request, Response] {
    def apply(request: Request): Future[Response] = {
      // We want to use the H2Classifier configured on the path stack so we load the classifier
      // from the request local context.
      val param.H2Classifier(classifier) =
        H2ClassifierCtx.current.getOrElse(param.H2Classifier.param.default)
      // If service has just been revived, accept no further requests.
      // Note: Another request may have come in before state transitions to
      // ProbeClosed, so > 1 requests may be processing while in the
      // ProbeClosed state. The result of first to complete will determine
      // whether the factory transitions to Alive (successful) or Dead
      // (unsuccessful).
      stopProbing()

      underlying(request).transform { rep =>
        Future.const(classifyResponse(classifier, H2ReqRep(request, rep)))
      }
    }

    override def close(deadline: Time): Future[Unit] = underlying.close(deadline)
    override def status: FStatus = FStatus.worst(
      underlying.status,
      H2FailureAccrualFactory.this.status
    )

    private[this] def classifyResponse(classifier: H2Classifier, h2ReqRep: H2ReqRep): Try[Response] =
      classifier.responseClassifier.lift(h2ReqRep) match {
        case Some(ResponseClass.Successful(_)) =>
          didSucceed()
          h2ReqRep.response
        case Some(ResponseClass.Failed(_)) =>
          didFail()
          h2ReqRep.response
        case None =>
          h2ReqRep.response match {
            case Return(rep) =>
              if (rep.stream.isEmpty) {
                classifyStream(classifier, H2ReqRepFrame(h2ReqRep.request, Return((rep, None))))
                Return(rep)
              } else {
                val stream = rep.stream.onFrame {
                  case Return(f) if f.isEnd =>
                    classifyStream(classifier, H2ReqRepFrame(h2ReqRep.request, Return((rep, Some(Return(f))))))
                  case Throw(e) =>
                    classifyStream(classifier, H2ReqRepFrame(h2ReqRep.request, Return((rep, Some(Throw(e))))))
                  case _ => ()
                }
                Return(Response(rep.headers, stream))
              }
            case Throw(e) =>
              classifyStream(classifier, H2ReqRepFrame(h2ReqRep.request, Throw(e)))
              Throw(e)
          }
      }

    private[this] def classifyStream(classifier: H2Classifier, h2ReqRepFrame: H2ReqRepFrame): Unit =
      classifier.streamClassifier(h2ReqRepFrame) match {
        case ResponseClass.Successful(_) => didSucceed()
        case ResponseClass.Failed(_) => didFail()
      }
  }

  override def status: FStatus = state match {
    case Alive | ProbeOpen => underlying.status
    case Dead | ProbeClosed => FStatus.Busy
  }

  protected[this] def getState: State = state

  private[this] def cancelReviveTimerTask(): Unit = self.synchronized {
    reviveTimerTask.foreach(_.cancel())
    reviveTimerTask = None
  }

  def close(deadline: Time): Future[Unit] = underlying.close(deadline).ensure {
    cancelReviveTimerTask()
  }

  override def toString = s"h2_failure_accrual_${underlying.toString}"
}
