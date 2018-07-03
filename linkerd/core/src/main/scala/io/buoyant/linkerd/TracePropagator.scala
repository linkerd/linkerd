package io.buoyant.linkerd

import com.twitter.finagle.buoyant.{Sampler, TraceInitializer}
import com.twitter.finagle.tracing.{Trace, TraceId, Tracer}

trait TracePropagator[Req] {

  /**
   * Read the trace id from the request, if it has one.
   */
  def traceId(req: Req): Option[TraceId]

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request.  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  def sampler(req: Req): Option[Sampler]

  /**
   * Write the trace id onto a request.
   */
  def setContext(req: Req, traceId: TraceId): Unit
}

object TracePropagator {

  def NilTracePropagator[Req] = new TracePropagator[Req] {
    override def traceId(req: Req): Option[TraceId] = None
    override def sampler(req: Req): Option[Sampler] = None
    override def setContext(req: Req, traceId: TraceId): Unit = ()
  }

  class ServerFilter[Req, Rep](tracePropagator: TracePropagator[Req], tracer: Tracer) extends TraceInitializer.ServerFilter[Req, Rep](tracer) {
    override def traceId(req: Req): Option[TraceId] = tracePropagator.traceId(req)
    override def sampler(req: Req): Option[Sampler] = tracePropagator.sampler(req)
  }

  class ClientFilter[Req, Rep](tracePropagator: TracePropagator[Req], tracer: Tracer) extends TraceInitializer.ClientFilter[Req, Rep](tracer: Tracer) {
    override def setContext(req: Req): Unit = tracePropagator.setContext(req, Trace.id)
  }
}
