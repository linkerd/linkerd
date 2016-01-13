package com.twitter.finagle.buoyant

import com.twitter.finagle.{Status => _, _}
import com.twitter.finagle.http._
import com.twitter.finagle.tracing._
import io.buoyant.router.http.Headers

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
   * The server reads the `buoy-ctx` header
   * (`io.buoyant.router.Headers.Ctx.Key`) to load trace information.
   */
  object server extends Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Attaches trace information to the outgoing request"

    class Filter(tracer: Tracer) extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) = {
        val headers = req.headerMap

        val ctx = Headers.Ctx.get(headers)
        Headers.Ctx.clear(headers)
        def withTracer[T](f: => T): T = ctx match {
          case None => Trace.letTracer(tracer)(f)
          case Some(parent) => Trace.letTracerAndId(tracer, parent)(f)
        }

        val sampledTracer = Headers.Sample.get(headers) match {
          case Some(rate) => SampledTracer(rate, tracer)
          case _ => tracer
        }
        Headers.Sample.clear(headers)

        def withTraceId[T](f: => T): T = {
          val id = Trace.nextId
          val sampledId = id.sampled match {
            case None => id.copy(_sampled = sampledTracer.sampleTrace(id))
            case Some(_) => id
          }
          Trace.letId(sampledId)(f)
        }

        // TODO remove Wire annotations after twitter/finagle#401 lands.
        withTracer {
          withTraceId {
            Trace.record(Annotation.WireRecv)
            service(req) onSuccess { _ =>
              Trace.record(Annotation.WireSend)
            }
          }
        }
      }
    }

    def make(_tracer: param.Tracer, next: ServiceFactory[Request, Response]) = {
      val param.Tracer(tracer) = _tracer
      new Filter(tracer) andThen next
    }
  }

  /**
   * So, on the client side, we only set headers and do no initialization.
   */
  object client extends Stack.Module1[param.Tracer, ServiceFactory[Request, Response]] {
    val role = HttpTraceInitializer.role
    val description = "Attaches trace information to the outgoing request"

    class Filter(tracer: Tracer) extends SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) =
        Trace.letTracer(tracer) {
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
