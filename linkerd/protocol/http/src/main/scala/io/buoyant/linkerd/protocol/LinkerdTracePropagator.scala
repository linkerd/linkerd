package io.buoyant.linkerd.protocol

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.Request
import com.twitter.finagle.tracing.TraceId
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}

class LinkerdTracePropagatorInitializer extends TracePropagatorInitializer {
  override val configClass = classOf[LinkerdTracePropagatorConfig]
  override val configId = "io.l5d.default"
}

case class LinkerdTracePropagatorConfig() extends HttpTracePropagatorConfig {
  override def mk(params: Stack.Params): TracePropagator[Request] = new LinkerdTracePropagator
}

class LinkerdTracePropagator extends TracePropagator[Request] {
  /**
   * Read the trace id from the request, if it has one.
   */
  override def traceId(req: Request): Option[TraceId] = {
    val traceId = Headers.Ctx.Trace.get(req.headerMap)
    Headers.Ctx.Trace.clear(req.headerMap)
    traceId
  }

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request.  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  override def sampler(req: Request): Option[Sampler] = {
    val sampler = Headers.Sample.get(req.headerMap).map(Sampler(_))
    Headers.Sample.clear(req.headerMap)
    sampler
  }

  /**
   * Write the trace id onto a request.
   */
  override def setContext(
    req: Request,
    traceId: TraceId
  ): Unit = {
    Headers.Ctx.Trace.set(req.headerMap, traceId)
    Headers.RequestId.set(req.headerMap, traceId)
  }
}
