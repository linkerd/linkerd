package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.buoyant.h2.{LinkerdHeaders, Request}
import com.twitter.finagle.tracing.TraceId
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}

class LinkerdTracePropagatorInitializer extends TracePropagatorInitializer {
  override val configClass = classOf[LinkerdTracePropagatorConfig]
  override val configId = "io.l5d.default"
}

case class LinkerdTracePropagatorConfig() extends H2TracePropagatorConfig {
  override def mk(params: Stack.Params): TracePropagator[Request] = new LinkerdTracePropagator
}

class LinkerdTracePropagator extends TracePropagator[Request] {
  /**
   * Read the trace id from the request, if it has one.
   */
  override def traceId(req: Request): Option[TraceId] = {
    val traceId = LinkerdHeaders.Ctx.Trace.get(req.headers)
    LinkerdHeaders.Ctx.Trace.clear(req.headers)
    traceId
  }

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request.  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  override def sampler(req: Request): Option[Sampler] = {
    val sampler = LinkerdHeaders.Sample.get(req.headers).map(Sampler(_))
    LinkerdHeaders.Sample.clear(req.headers)
    sampler
  }

  /**
   * Write the trace id onto a request.
   */
  override def setContext(
    req: Request,
    traceId: TraceId
  ): Unit = {
    LinkerdHeaders.Ctx.Trace.set(req.headers, traceId)
    LinkerdHeaders.RequestId.set(req.headers, traceId)
  }
}
