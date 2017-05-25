package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.{ServiceFactory, Stack, Stackable}
import com.twitter.finagle.buoyant.{Sampler, TraceInitializer}
import com.twitter.finagle.tracing._

object H2TraceInitializer {
  val role = TraceInitializerFilter.role

  class ServerFilter(tracer: Tracer, defaultSampler: Option[Sampler] = None)
    extends TraceInitializer.ServerFilter[Request, Response](tracer, defaultSampler) {

    override def traceId(req: Request): Option[TraceId] = {
      val traceId = LinkerdHeaders.Ctx.Trace.get(req.headers)
      LinkerdHeaders.Ctx.Trace.clear(req.headers)
      traceId
    }

    override def sampler(req: Request): Option[Sampler] = {
      val sampler = LinkerdHeaders.Sample.get(req.headers).map(Sampler(_))
      LinkerdHeaders.Sample.clear(req.headers)
      sampler
    }
  }

  class ClientFilter(tracer: Tracer) extends TraceInitializer.ClientFilter[Request, Response](tracer) {
    override def setContext(req: Request): Unit = {
      LinkerdHeaders.Ctx.Trace.set(req.headers, Trace.id)
      LinkerdHeaders.RequestId.set(req.headers, Trace.id)
    }
  }

  /**
   * The server reads the ctx header ([Headers.Ctx.Key]) to load
   * trace information.
   */
  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
      val role = H2TraceInitializer.role
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
      val role = H2TraceInitializer.role
      val description = "Attaches trace information to the outgoing request"
      def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
        val param.Tracer(tracer) = _tracer
        new ClientFilter(tracer) andThen next
      }
    }
}
