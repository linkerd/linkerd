package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.http._
import com.twitter.finagle.tracing._
import com.twitter.finagle.buoyant.SampledTracer

/**
 * Typically, finagle clients initialize trace ids to capture a
 * request-response flow.  This doesn't really fit with what we want
 * to capture in the router.  Specifically, we want to capture the
 * 'request' and 'response' portions of a trace individually--ingress
 * to egress in each direction.
 */
object HttpTraceInitializer {
  val role = TraceInitializerFilter.role

  object clear extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Clears all tracing info"
    def make(next: ServiceFactory[Request, Response]) = filter andThen next
    val filter = Filter.mk[Request, Response, Request, Response] {
      (req, service) => Trace.letClear(service(req))
    }
  }

  /**
   * The server reads the ctx header ([Headers.Ctx.Key]) to load
   * trace information.
   */
  object server extends Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Reads trace information from incoming request"

    class Filter(tracer0: Tracer) extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) = {
        val headers = req.headerMap
        val ctx = Headers.Ctx.get(headers)
        Headers.Ctx.clear(headers)
        val tracer = Headers.Sample.get(headers) match {
          case Some(rate) => SampledTracer(rate, tracer0)
          case _ => tracer0
        }
        Headers.Sample.clear(headers)

        withTracer(tracer, ctx) {
          setId(tracer, Trace.id) {
            service(req)
          }
        }
      }

      private[this] def withTracer[T](tracer: Tracer, parent: Option[TraceId])(f: => T) =
        parent match {
          case None => Trace.letTracer(tracer)(f)
          case Some(parent) => Trace.letTracerAndId(tracer, parent)(f)
        }

      private[this] def setId[T](tracer: Tracer, id0: TraceId)(f: => T) = {
        val id = id0.sampled match {
          case Some(s) => id0
          case None => id0.copy(_sampled = tracer.sampleTrace(id0))
        }
        Trace.letId(id)(f)
      }
    }

    def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
      val param.Tracer(tracer) = _tracer
      new Filter(tracer) andThen next
    }
  }

  /**
   * So, on the client side, we set headers after initializing a new context.
   */
  object client extends Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Attaches trace information to the outgoing request"

    class Filter(tracer: Tracer) extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) =
        Trace.letTracerAndNextId(tracer) {
          Headers.Ctx.set(req.headerMap, Trace.id)
          Headers.RequestId.set(req.headerMap, Trace.id.traceId)
          service(req)
        }
    }

    def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
      val param.Tracer(tracer) = _tracer
      new Filter(tracer) andThen next
    }
  }
}
