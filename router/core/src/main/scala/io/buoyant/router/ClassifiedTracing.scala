package io.buoyant.router

import com.twitter.finagle.{Stack, Stackable, Service, ServiceFactory, SimpleFilter, param}
import com.twitter.finagle.tracing.{Trace, Tracer}
import com.twitter.finagle.service.{ReqRep, ResponseClass, ResponseClassifier}
import com.twitter.util.{Duration, Future}

object ClassifiedTracing {
  val role = Stack.Role("ClassifiedTracing")

  /**
   * Traces response classifications as one of the following:
   * - l5d.success
   * - l5d.retryable
   * - l5d.failure
   */
  private class Filter[Req, Rsp](classifier: ResponseClassifier) extends SimpleFilter[Req, Rsp] {
    def apply(req: Req, service: Service[Req, Rsp]): Future[Rsp] = {
      val f = service(req)
      if (!Trace.isActivelyTracing) f
      else f.respond { rsp =>
        classifier.applyOrElse(ReqRep(req, rsp), ResponseClassifier.Default) match {
          case ResponseClass.Successful(fraction) =>
            Trace.recordBinary("l5d.success", fraction)
          case ResponseClass.Failed(retryable) =>
            if (retryable) Trace.record("l5d.retryable")
            else Trace.record("l5d.failure")
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

