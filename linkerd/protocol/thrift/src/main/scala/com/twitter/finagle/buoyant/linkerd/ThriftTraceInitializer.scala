package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.tracing.{Trace, TraceInitializerFilter, Tracer}

object ThriftTraceInitializer {
  val role = TraceInitializerFilter.role

  def serverModule[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Req, Rep]] {
      val role = ThriftTraceInitializer.role
      val description = "Ensure that there is a trace id set"

      def make(_tracer: param.Tracer, next: ServiceFactory[Req, Rep]) = {
        val param.Tracer(tracer) = _tracer
        new ServerFilter(tracer) andThen next
      }
    }

  class ServerFilter[Req, Rep](tracer: Tracer)
    extends SimpleFilter[Req, Rep] {

    def apply(req: Req, service: Service[Req, Rep]) = {
      if (!Trace.hasId)
        Trace.letTracerAndNextId(tracer) {
          service(req)
        }
      else service(req)
    }
  }
}
