package io.buoyant.linkerd.protocol.h2

import java.util.Base64

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.buoyant.h2.{Headers, LinkerdHeaders, Request}
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId}
import com.twitter.util.Try
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}

class ZipkinTracePropagatorInitializer extends TracePropagatorInitializer {
  override val configClass = classOf[ZipkinTracePropagatorConfig]
  override val configId = "io.l5d.zipkin"
}

case class ZipkinTracePropagatorConfig() extends H2TracePropagatorConfig {
  override def mk(params: Stack.Params): TracePropagator[Request] = new ZipkinTracePropagator
}

class ZipkinTracePropagator extends LinkerdTracePropagator {
  /**
   * Read the trace id from the request, if it has one.
   */
  override def traceId(req: Request): Option[TraceId] = {
    var traceId = ZipkinTrace.get(req.headers)
    // retro compatible.
    if (traceId.isEmpty) {
      traceId = super.traceId(req)
    } else {
      LinkerdHeaders.Ctx.Trace.clear(req.headers)
    }
    traceId
  }

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request (zipkin or linkerd if zipkin not present).  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  override def sampler(req: Request): Option[Sampler] = {
    var sampler = ZipkinTrace.getSampler(req.headers).map(Sampler(_))
    // retro compatible
    if (sampler.isEmpty) {
      sampler = super.sampler(req)
    } else {
      LinkerdHeaders.Sample.clear(req.headers)
    }
    sampler
  }

  /**
   * Write the trace id onto a request.
   */
  override def setContext(
    req: Request,
    traceId: TraceId
  ): Unit = {
    super.setContext(req, traceId)
    //always set header from here on
    ZipkinTrace.set(req.headers, traceId)
  }
}

object ZipkinTrace {

  val ZipkinSpanHeader = "x-b3-spanid"
  val ZipkinParentHeader = "x-b3-parentspanid"
  val ZipkinTraceHeader = "x-b3-traceid"
  val ZipkinSampleHeader = "x-b3-sampled"
  val ZipkinFlagsHeader = "x-b3-flags"

  def get(headers: Headers): Option[TraceId] =
    Try(TraceId.apply(SpanId.fromString(headers.get(ZipkinTraceHeader).get), SpanId.fromString(headers.get(ZipkinParentHeader).get), SpanId.fromString(headers.get(ZipkinSpanHeader).get).get, Some(if (headers.get(ZipkinSampleHeader).get.toInt == 1) true else false), Flags.apply(headers.get(ZipkinFlagsHeader).get.toInt))).toOption

  def set(headers: Headers, id: TraceId): Unit = {
    val _ = headers.set(ZipkinSpanHeader, id.spanId.toString)
    val __ = headers.set(ZipkinTraceHeader, id.traceId.toString)
    val ___ = headers.set(ZipkinParentHeader, id.parentId.toString)
  }

  def getSampler(headers: Headers): Option[Float] =
    headers.get(ZipkinSampleHeader).flatMap { s =>
      Try(s.toFloat).toOption.map {
        case v if v < 0 => 0.0f
        case v if v > 1 => 1.0f
        case v => v
      }
    }

}