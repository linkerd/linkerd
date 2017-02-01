package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.mux.{Request, Response}
import com.twitter.finagle.tracing.{Trace, TraceInitializerFilter, Tracer}
import com.twitter.util.Future

object ThriftMuxTraceInitializer {
  val role = TraceInitializerFilter.role

  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = ThriftMuxTraceInitializer.role
      val description = "Ensure that there is a trace id set"

      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        new ServerFilter(tracer) andThen next
      }
    }

  class ServerFilter(tracer: Tracer) extends SimpleFilter[Request, Response] {
    def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
      if (!Trace.hasId) Trace.letTracerAndNextId(tracer) { service(req) }
      else service(req)
    }
  }
}