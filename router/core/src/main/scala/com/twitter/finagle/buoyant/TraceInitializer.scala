package com.twitter.finagle.buoyant

import com.twitter.finagle.tracing.{Trace, TraceId, Tracer}
import com.twitter.finagle.{Service, SimpleFilter}

object TraceInitializer {

  abstract class ServerFilter[Req, Rsp](tracer: Tracer, defaultSampler: Option[Sampler] = None)
    extends SimpleFilter[Req, Rsp] {

    def traceId(req: Req): Option[TraceId]
    def sampler(req: Req): Option[Sampler]

    /**
     * Establish context for this request, as follows:
     * 1. Set the trace id from the context header, if one was provided.
     * 2. Get a new span id for the current request.
     * 3. Use the sample header to determine if the request should be sampled.
     */
    def apply(req: Req, service: Service[Req, Rsp]) = {
      Trace.letIdOption(traceId(req)) {
        Trace.letTracerAndNextId(tracer) {
          sample(sampler(req).orElse(defaultSampler)) {
            service(req)
          }
        }
      }
    }

    /**
     * Only set _sampled on the trace ID if the sample header provided a
     * sample rate, the sampler determines that the request should be
     * sampled based on the sample rate, and the _sampled field is unset on
     * the current trace ID.
     */
    def sample[T](sampler: Option[Sampler])(f: => T) =
      sampler match {
        case None => f
        case Some(sampler) =>
          val id = Trace.id
          val sampled = id.copy(_sampled = Some(sampler(id.traceId.toLong)))
          Trace.letId(sampled)(f)
      }
  }

  abstract class ClientFilter[Req, Rep](tracer: Tracer)
    extends SimpleFilter[Req, Rep] {

    def setContext(req: Req): Unit

    def apply(req: Req, service: Service[Req, Rep]) =
      Trace.letTracerAndNextId(tracer) {
        setContext(req)
        service(req)
      }
  }
}
