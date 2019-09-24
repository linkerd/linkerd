package io.buoyant.linkerd.protocol.h2

import java.util.Base64

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.buoyant.h2.{Headers, LinkerdHeaders, Request}
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import com.twitter.util.Try
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}

class ZipkinTracePropagatorInitializer extends TracePropagatorInitializer {
  override val configClass = classOf[ZipkinTracePropagatorConfig]
  override val configId = "io.l5d.zipkin"
}

case class ZipkinTracePropagatorConfig() extends H2TracePropagatorConfig {
  override def mk(params: Stack.Params): TracePropagator[Request] = new ZipkinTracePropagator
}

class ZipkinTracePropagator extends TracePropagator[Request] {
  /**
   * Read the trace id from the request, if it has one.
   */
  override def traceId(req: Request): Option[TraceId] = {
    var traceId = ZipkinTrace.get(req.headers)
    LinkerdHeaders.Ctx.Trace.clear(req.headers)
    traceId
  }

  /**
   * Return a sampler which decides if the given request should be sampled, based on properties
   * of the request (zipkin or linkerd if zipkin not present).  If None is returned, the decision of whether to sample the request is deferred
   * to the tracer.
   */
  override def sampler(req: Request): Option[Sampler] = {
    var sampler = ZipkinTrace.getSampler(req.headers).map(Sampler(_))
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
    ZipkinTrace.set(req.headers, traceId)
  }
}

object ZipkinTrace {

  val ZipkinSpanHeader = "x-b3-spanid"
  val ZipkinParentHeader = "x-b3-parentspanid"
  val ZipkinTraceHeader = "x-b3-traceid"
  val ZipkinSampleHeader = "x-b3-sampled"
  val ZipkinFlagsHeader = "x-b3-flags"

  /**
   * The separate "b3" header in b3 single header format :
   * b3={x-b3-traceid}-{x-b3-spanid}-{if x-b3-flags 'd' else x-b3-sampled}-{x-b3-parentspanid},
   * where the last two fields are optional.
   */
  val ZipkinB3SingleHeader = "b3"

  /**
   * Handle b3 the same way as finagle handles b3, extract the traceid from "b3" header, write back "X-B3-*" multi
   * headers, and let the flow continue as if it has received "x-b3-".
   * @note Copied/adapted from finagle TraceInfo.scala
   */
  def convertB3SingleHeaderToMultiHeaders(headers: Headers): Unit = {
    if (!caseInsensitiveGet(headers, ZipkinB3SingleHeader).isEmpty) {
      def handleSampled(headers: Headers, value: String): Unit = {
        value match {
          case "0" =>
            headers.set(ZipkinSampleHeader, "0")
          case "d" =>
            headers.set(ZipkinFlagsHeader, "1")
          case "1" =>
            headers.set(ZipkinSampleHeader, "1")
          case _ =>
            () // do not set anything on invalid value
        }
        ()
      }

      def handleTraceAndSpanIds(headers: Headers, a: String, b: String): Unit = {
        headers.set(ZipkinTraceHeader, a)
        headers.set(ZipkinSpanHeader, b)
        ()
      }

      caseInsensitiveGet(headers, ZipkinB3SingleHeader).map(_.split("-")) match {
        case Some(a) =>
          a.size match {
            case 1 =>
              // either debug flag or sampled
              handleSampled(headers, a(0))
            case 2 =>
              // this is required to be traceId, spanId
              handleTraceAndSpanIds(headers, a(0), a(1))
            case 3 =>
              handleTraceAndSpanIds(headers, a(0), a(1))
              handleSampled(headers, a(2))
            case 4 =>
              handleTraceAndSpanIds(headers, a(0), a(1))
              handleSampled(headers, a(2))
              headers.set(ZipkinParentHeader, a(3))
            case _ =>
              // bogus, do not handle the case when b3 is empty or has more than 4 components
              ()
          }
        case None =>
          ()
      }
      val _ = headers.remove(ZipkinB3SingleHeader)
    }
  }

  /**
   * Get a trace id from the request, if it has one, in either the historical multiple "x-b3-" headers or the
   * newer "b3" single header. The "b3" single-header variant takes precedence over the multiple header one
   * when extracting fields, that also implies ignoring the latter if "b3" single header is read.
   * There's no way to know what the downstream is capable of, and it is more likely it supports "X-B3-*" vs not, so,
   * the portable choice is to always write down "X-B3-"  even if we read "b3". Finagle does a similar thing in
   * finagle-http-base: read "b3", write back "X-B3-".
   * @note The "b3" encodings specs, single or multi: https://github.com/openzipkin/b3-propagation#http-encodings
   * @param headers
   * @return Option[TraceId]
   */
  def get(headers: Headers): Option[TraceId] = {
    //if "b3" present convert it to "x-b3-" multio and let the flow continue as if it has received "x-b3-"
    convertB3SingleHeaderToMultiHeaders(headers)

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

  def set(headers: Headers, id: TraceId): Unit = {
    headers.set(ZipkinSpanHeader, id.spanId.toString)

    // support setting a 128bit traceid
    if (id.traceIdHigh.isEmpty) {
      headers.set(ZipkinTraceHeader, id.traceId.toString)
    } else {
      headers.set(ZipkinTraceHeader, id.traceIdHigh.get.toString + id.traceId.toString)
    }

    headers.set(ZipkinParentHeader, id.parentId.toString)
    headers.set(ZipkinSampleHeader, (if ((id.sampled exists { _ == true })) 1 else 0).toString)
    headers.set(ZipkinFlagsHeader, id.flags.toLong.toString)
    ()
  }

  /**
   * This method will also work on requests containing "b3" single header, because after the header is read and
   * processed the "X-B3-Sampled" is written back onto request using the "Sampled" value found in the "b3" header.
   */
  def getSampler(headers: Headers): Option[Float] =
    headers.get(ZipkinSampleHeader).flatMap { s =>
      Try(s.toFloat).toOption.map {
        case v if v < 0 => 0.0f
        case v if v > 1 => 1.0f
        case v => v
      }
    }

  private def caseInsensitiveGet(headers: Headers, key: String): Option[String] =
    headers.toSeq.iterator.collectFirst { case (k, v) if key.equalsIgnoreCase(k) => v }
}
