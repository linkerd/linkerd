package io.buoyant.router

import com.twitter.finagle.{Stack, Stackable, Service, ServiceFactory, SimpleFilter, param}
import com.twitter.finagle.tracing.{Trace, Tracer}
import com.twitter.finagle.service.{ReqRep, ResponseClass, ResponseClassifier}
import com.twitter.util.{Duration, Future}

object ClassifiedTracing {
  val role = Stack.Role("ClassifiedTracing")

  /**
   * A RetryPolicy that uses a ResponseClassifier.
   */
  private class Filter[Req, Rsp](classifier: ResponseClassifier) extends SimpleFilter[Req, Rsp] {
    def apply(req: Req, service: Service[Req, Rsp]): Future[Rsp] =
      service(req).respond { rsp =>
        if (Trace.isActivelyTracing) {
          classifier.applyOrElse(ReqRep(req, rsp), ResponseClassifier.Default) match {
            case ResponseClass.Successful(fraction) =>
              Trace.recordBinary("l5d.success", fraction)
            case ResponseClass.Failed(true) =>
              Trace.record("l5d.retryable")
            case ResponseClass.Failed(false) =>
              Trace.record("l5d.failed")
          }
        }
      }
  }

  /**
   * A stack module that, if a tracer is enabled, annotates traces
   * based on response classifications.
   */
  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module2[param.Tracer, param.ResponseClassifier, ServiceFactory[Req, Rsp]] {
      val role = ClassifiedTracing.role
      val description = "Traces response classification annotations"
      def make(
        _tracer: param.Tracer,
        _classifier: param.ResponseClassifier,
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = {
        val param.Tracer(tracer) = _tracer
        if (tracer.isNull) next
        else {
          val param.ResponseClassifier(classifier) = _classifier
          new Filter[Req, Rsp](classifier) andThen next
        }
      }
    }
}

