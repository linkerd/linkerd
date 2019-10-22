package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{SimpleFilter, Stack}
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.tracing.{Flags, SpanId, Trace, TraceId, Tracer}
import com.twitter.util.Try
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}
import io.buoyant.router.http.{HeadersLike, RequestLike}

class ZipkinTracePropagator[Req, H: HeadersLike](implicit requestLike: RequestLike[Req, H]) extends TracePropagator[Req] {
  /**
   * Read the trace id from the request, if it has one.
   */
  override def traceId(req: Req): Option[TraceId] = {
    var traceId = ZipkinTrace.get(requestLike.headers(req))
    ZipkinTrace.clear(requestLike.headers(req))
    traceId
  }

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request (zipkin or linkerd if zipkin not present).  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  override def sampler(req: Req): Option[Sampler] = {
    if (Trace.hasId) {
      Trace.id.sampled.map {
        case true => Sampler(1.0f)
        case false => Sampler(0.0f)
      }
    } else {
      None
    }
  }

  /**
   * Write the trace id onto a request.
   */
  override def setContext(
    req: Req,
    traceId: TraceId
  ): Unit = {
    ZipkinTrace.set(requestLike.headers(req), traceId)
  }
}

object ZipkinTrace {

  val ZipkinSpanHeader = "x-b3-spanid"
  val ZipkinParentHeader = "x-b3-parentspanid"
  val ZipkinTraceHeader = "x-b3-traceid"
  val ZipkinSampleHeader = "x-b3-sampled"
  val ZipkinFlagsHeader = "x-b3-flags"

  def get[H: HeadersLike](headers: H): Option[TraceId] = {
    val trace = caseInsensitiveGet(headers, ZipkinTraceHeader).flatMap(SpanId.fromString)
    val parent = caseInsensitiveGet(headers, ZipkinParentHeader).flatMap(SpanId.fromString)
    val span = caseInsensitiveGet(headers, ZipkinSpanHeader).flatMap(SpanId.fromString)
    val sample = caseInsensitiveGet(headers, ZipkinSampleHeader).map(StringUtil.toBoolean)
    val flags = caseInsensitiveGet(headers, ZipkinFlagsHeader).map(StringUtil.toSomeLong) match {
      case Some(f) => Flags(f)
      case None => Flags()
    }
    span.map { s =>
      TraceId(trace, parent, s, sample, flags)
    }
  }

  def clear[H: HeadersLike](headers: H): Unit = {
    val headersLike = implicitly[HeadersLike[H]]

    caseInsensitiveGetKey(headers, ZipkinSpanHeader).map(headersLike.remove(headers, _))
    caseInsensitiveGetKey(headers, ZipkinTraceHeader).map(headersLike.remove(headers, _))
    caseInsensitiveGetKey(headers, ZipkinParentHeader).map(headersLike.remove(headers, _))
    caseInsensitiveGetKey(headers, ZipkinSampleHeader).map(headersLike.remove(headers, _))
    caseInsensitiveGetKey(headers, ZipkinFlagsHeader).map(headersLike.remove(headers, _))
    ()
  }

  def set[H: HeadersLike](headers: H, id: TraceId): Unit = {
    val headersLike = implicitly[HeadersLike[H]]

    headersLike.set(headers, ZipkinSpanHeader, id.spanId.toString)
    headersLike.set(headers, ZipkinTraceHeader, id.traceId.toString)
    headersLike.set(headers, ZipkinParentHeader, id.parentId.toString)
    headersLike.set(headers, ZipkinSampleHeader, (if ((id.sampled exists { _ == true })) 1 else 0).toString)
    headersLike.set(headers, ZipkinFlagsHeader, id.flags.toLong.toString)
    ()
  }

  private def caseInsensitiveGetKey[H: HeadersLike](headers: H, key: String): Option[String] = {
    val headersLike = implicitly[HeadersLike[H]]
    headersLike.toSeq(headers).iterator.collectFirst { case (k, v) if key.equalsIgnoreCase(k) => k }
  }

  private def caseInsensitiveGet[H: HeadersLike](headers: H, key: String): Option[String] = {
    val headersLike = implicitly[HeadersLike[H]]
    headersLike.toSeq(headers).iterator.collectFirst { case (k, v) if key.equalsIgnoreCase(k) => v }
  }
}
