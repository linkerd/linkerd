package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.{ServiceFactory, Stack, Stackable, param}
import com.twitter.finagle.buoyant.{Sampler, TraceInitializer}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing._

/**
 * Uses [[Headers.Ctx.Trace]] and [[Headers.Sample]] headers to
 * propagate/control tracing.
 */
object HttpTraceInitializer {
  val role = TraceInitializerFilter.role

  class ServerFilter(tracer: Tracer, defaultSampler: Option[Sampler] = None)
    extends TraceInitializer.ServerFilter[Request, Response](tracer, defaultSampler) {

    override def traceId(req: Request): Option[TraceId] = {
      val traceId = Headers.Ctx.Trace.get(req.headerMap)
      Headers.Ctx.Trace.clear(req.headerMap)
      traceId
    }

    override def sampler(req: Request): Option[Sampler] = {
      val sampler = Headers.Sample.get(req.headerMap).map(Sampler(_))
      Headers.Sample.clear(req.headerMap)
      sampler
    }
  }

  class ClientFilter(tracer: Tracer) extends TraceInitializer.ClientFilter[Request, Response](tracer) {
    override def setContext(req: Request): Unit = {
      Headers.Ctx.Trace.set(req.headerMap, Trace.id)
      Headers.RequestId.set(req.headerMap, Trace.id)
    }
  }

  /**
   * The server reads the ctx header ([Headers.Ctx.Key]) to load
   * trace information.
   */
  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = HttpTraceInitializer.role
      val description = "Reads trace information from incoming request"

      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        new ServerFilter(tracer) andThen next
      }
    }

  /**
   * So, on the client side, we set headers after initializing a new context.
   */
  val clientModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = HttpTraceInitializer.role
      val description = "Attaches trace information to the outgoing request"
      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        new ClientFilter(tracer) andThen next
      }
    }
}
