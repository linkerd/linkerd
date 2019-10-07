package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{SimpleFilter, Stack}
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import com.twitter.util.Try
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}
import io.buoyant.router.http.{HeadersLike, RequestLike}
import com.twitter.logging.Logger

class ZipkinTracePropagator[Req, H: HeadersLike](implicit requestLike: RequestLike[Req, H]) extends TracePropagator[Req] {
  /**
   * Read the trace id from the request, if it has one.
   */
  override def traceId(req: Req): Option[TraceId] = {
    var traceId = ZipkinTrace.get(requestLike.headers(req))

    traceId
  }

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request (zipkin or linkerd if zipkin not present).  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  override def sampler(req: Req): Option[Sampler] = {
    var sampler = ZipkinTrace.getSampler(requestLike.headers(req)).map(Sampler(_))

    sampler
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
    val headersLike = implicitly[HeadersLike[H]]

    // expect to read a 128bit traceid field, b3 single header supports 128bit traceids
    val trace128Bit = caseInsensitiveGet(headers, ZipkinTraceHeader) match {
      case Some(s) => TraceId128(s)
      case None => TraceId128.empty
    }

    val parent = caseInsensitiveGet(headers, ZipkinParentHeader).flatMap(SpanId.fromString)
    val span = caseInsensitiveGet(headers, ZipkinSpanHeader).flatMap(SpanId.fromString)
    val sample = caseInsensitiveGet(headers, ZipkinSampleHeader).map(StringUtil.toBoolean)
    val flags = caseInsensitiveGet(headers, ZipkinFlagsHeader).map(StringUtil.toSomeLong) match {
      case Some(f) => Flags(f)
      case None => Flags()
    }

    span.map { s =>
      TraceId(trace128Bit.low, parent, s, sample, flags, trace128Bit.high)
    }
  }

  def set[H: HeadersLike](headers: H, id: TraceId): Unit = {
    val headersLike = implicitly[HeadersLike[H]]

    headersLike.set(headers, ZipkinSpanHeader, id.spanId.toString)

    // support setting a 128bit traceid
    if (id.traceIdHigh.isEmpty) {
      headersLike.set(headers, ZipkinTraceHeader, id.traceId.toString)
    } else {
      headersLike.set(headers, ZipkinTraceHeader, id.traceIdHigh.get.toString + id.traceId.toString)
    }

    headersLike.set(headers, ZipkinParentHeader, id.parentId.toString)

    //the only valid value for x-b3-flags is 1, which means debug
    if (id.flags.isDebug) {
      //when setting x-b3-flags to debug, 1, do not also set x-b3-sampled because it is a redundant info
      headersLike.set(headers, ZipkinFlagsHeader, id.flags.toLong.toString)
    } else {
      id.sampled match {
        // valid values fro x-b3-sampled are 1 and 0
        case Some(true) => headersLike.set(headers, ZipkinSampleHeader, "1")
        case Some(false) => headersLike.set(headers, ZipkinSampleHeader, "0")
        case None => // do nothing
      }
    }
    ()
  }

  def getSampler[H: HeadersLike](headers: H): Option[Float] = {
    val headersLike = implicitly[HeadersLike[H]]
    val samplerNone: Option[Float] = None
    val samplerTrue: Option[Float] = Option(1.0f)
    val samplerFalse: Option[Float] = Option(0.0f)

    // first try getting x-b3-flags, flags = 1 means debug
    val flags = caseInsensitiveGet(headers, ZipkinFlagsHeader)
    if (flags.isEmpty) {
      // try getting x-b3-sampled only if x-b3-flags not present
      caseInsensitiveGet(headers, ZipkinSampleHeader).flatMap { s =>
        Try(s.toFloat).toOption match {
          //x-b3-sampled present, the only valid values for x-b3-sampled are 0 and 1, any other values are invalid and should be ignored
          case Some(v) if v == 0 => samplerFalse
          case Some(v) if v == 1 => samplerTrue
          case _ => samplerNone
        }
      }
    } else {
      flags.flatMap { s =>
        Try(s.toLong).toOption match {
          //x-b3-flags present, the only valid value for x-b3-flags is 1, any other values are invalid and should be ignored
          case Some(v) if v == 1 => samplerTrue
          case _ => samplerNone
        }
      }
    }
  }

  private def caseInsensitiveGet[H: HeadersLike](headers: H, key: String): Option[String] = {
    val headersLike = implicitly[HeadersLike[H]]
    headersLike.iterator(headers).collectFirst { case (k, v) if key.equalsIgnoreCase(k) => v }
  }
}
