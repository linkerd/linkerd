package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.tracing.Trace

object TracingFilter {
  val role = StackClient.Role.protoTracing
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = TracingFilter.role
      val description = "Traces HTTP/2-specific request metadata"
      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        if (tracer.isNull) next
        else (new TracingFilter).andThen(next)
      }
    }
}

class TracingFilter extends SimpleFilter[Request, Response] {

  def apply(req: Request, service: Service[Request, Response]) = {
    recordRequest(req)
    service(req).onSuccess(recordResponseFn)
  }

  private[this] def recordRequest(req: Request): Unit =
    if (Trace.isActivelyTracing) {
      Trace.recordRpc(s"${req.method.toString} ${req.path}")
      Trace.recordBinary("h2.req.authority", req.authority)
    }

  private[this] def recordResponse(rsp: Response): Unit =
    if (Trace.isActivelyTracing) {
      Trace.recordBinary("h2.rsp.status", rsp.status.code)
    }

  private[this] val recordResponseFn: Response => Unit = recordResponse _
}
