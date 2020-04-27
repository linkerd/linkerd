package io.buoyant.router

import com.twitter.finagle.{Stack, Stackable, Service, ServiceFactory, SimpleFilter, param, Path}
import com.twitter.finagle.tracing.{Trace, Tracer}
import com.twitter.finagle.service.{ReqRep, ResponseClass, ResponseClassifier}
import com.twitter.util.{Duration, Future}
import io.buoyant.router.context.ResponseClassifierCtx

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
          case ResponseClass.Ignorable =>
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
    new Stack.Module1[param.Tracer, ServiceFactory[Req, Rsp]] {
      val role = ClassifiedTracing.role
      val description = "Traces response classification annotations"
      def make(
        _tracer: param.Tracer,
        next: ServiceFactory[Req, Rsp]
      ): ServiceFactory[Req, Rsp] = {
        val param.Tracer(tracer) = _tracer
        if (tracer.isNull) next
        else {

          // We memoize on the dst path.  The assumes that the response classifier for a dst path
          // never changes.
          def mkClassifiedTracingFilter(path: Path) = {
            val param.ResponseClassifier(classifier) =
              ResponseClassifierCtx.current.getOrElse(param.ResponseClassifier.param.default)
            new Filter[Req, Rsp](classifier)
          }

          val filter = new PerDstPathFilter(mkClassifiedTracingFilter _)
          filter.andThen(next)
        }
      }
    }
}

