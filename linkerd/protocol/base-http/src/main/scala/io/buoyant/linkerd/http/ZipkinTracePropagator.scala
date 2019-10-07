package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{SimpleFilter, Stack}
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import com.twitter.util.Try
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}
import io.buoyant.router.http.{HeadersLike, RequestLike}

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
   * of the request (zipkin).  If None is returned, the decision of whether to sample the request is deferred
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

  /**
   * The separate "b3" header in b3 single header format :
   * b3={x-b3-traceid}-{x-b3-spanid}-{if x-b3-flags 'd' else x-b3-sampled}-{x-b3-parentspanid},
   * where the last two fields are optional.
   */
  val ZipkinB3SingleHeader = "b3"

  /**
   * Extract a traceid from the Zipkin b3 single header. If no traceid try returning the sampled/flags info.
   * @note A traceid cannot be constructed if no spanid is present.
   * @param b3SingleHeader
   * @tparam H
   * @return A tuple of three values (traceid, sampled, flags)
   */
  def getFromB3SingleHeader[H: HeadersLike](b3SingleHeader: String): (Option[TraceId], Option[Boolean], Flags) = {
    /* extract from the b3 single header the values of sampled and flags which closely
    * match the behavior from X-B3 multi headers, X-B3-Sampled and X-B3-Flags */
    def matchSampledAndFlags(value: String): (Option[Boolean], Flags) = {
      value match {
        case "0" => (Option(false), Flags())
        case "d" => (None, Flags(1))
        case "1" => (Option(true), Flags())
        case _ => (None, Flags())
      }
    }

    b3SingleHeader.split("-").toList match {
      case sampled :: Nil =>
        // only debug flag or sampled
        val (sampled_, flags) = matchSampledAndFlags(sampled)
        (None, sampled_, flags)

      case traceId :: spanId :: Nil =>
        // expect to read a 128bit traceid field, b3 single header supports 128bit traceids
        val trace128Bit = TraceId128(traceId)
        val (sampled_, flags) = matchSampledAndFlags("")
        (SpanId.fromString(spanId).map(sid => TraceId(trace128Bit.low, None, sid, None, Flags(), trace128Bit.high)), sampled_, flags)

      case traceId :: spanId :: sampled :: Nil =>
        // expect to read a 128bit traceid field, b3 single header supports 128bit traceids

        val trace128Bit = TraceId128(traceId)
        val (sampled_, flags) = matchSampledAndFlags(sampled)

        (SpanId.fromString(spanId).map(sid => TraceId(trace128Bit.low, None, sid, sampled_, flags, trace128Bit.high)), sampled_, flags)

      case traceId :: spanId :: sampled :: parentId :: Nil =>
        // expect to read a 128bit traceid field, b3 single header supports 128bit traceids
        val trace128Bit = TraceId128(traceId)
        val (sampled_, flags) = matchSampledAndFlags(sampled)

        (SpanId.fromString(spanId).map(sid => TraceId(trace128Bit.low, SpanId.fromString(parentId), sid, sampled_, flags, trace128Bit.high)), sampled_, flags)

      case _ =>
        // bogus, do not handle the case when b3 is empty or has more than 4 components
        (None, None, Flags())
    }
  }

  def getFromXB3MultiHeaders[H: HeadersLike](headers: H): Option[TraceId] = {
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

  /**
   * Get a trace id from the request, if it has one, in either the historical multiple "x-b3-" headers or the
   * newer "b3" single header. The "b3" single-header variant takes precedence over the multiple header one
   * when extracting fields, that also implies ignoring the latter if "b3" single header is read.
   *
   * @note This method does not touches the request, b3 header is not remove, x-b3- headers not removed
   * @note The "b3" encodings specs, single or multi: https://github.com/openzipkin/b3-propagation#http-encodings
   * @param headers
   * @return Option[TraceId]
   */
  def get[H: HeadersLike](headers: H): Option[TraceId] = {
    val headersLike = implicitly[HeadersLike[H]]

    caseInsensitiveGet(headers, ZipkinB3SingleHeader) match {
      case Some(v) => {
        val (traceId, sampled, flags) = getFromB3SingleHeader(v)
        traceId
      }
      case _ => getFromXB3MultiHeaders(headers)
    }
  }

  /**
   * There's no way to know what the downstream is capable of, and it is more likely it supports "X-B3-*" vs not, so,
   * the portable choice is to always write down "X-B3-"  even if we read "b3". Finagle does a similar thing in
   * finagle-http-base: read "b3", write back "X-B3-".
   *
   * @note This method does not touches the request, b3 header is not remove, x-b3- headers not removed
   * @note The "b3" encodings specs, single or multi: https://github.com/openzipkin/b3-propagation#http-encodings
   * @param headers
   * @return Option[TraceId]
   */

  def set[H: HeadersLike](headers: H, id: TraceId): Unit = {
    val headersLike = implicitly[HeadersLike[H]]

    // remove any b3 single header, if present, before setting x-b3 multi headers
    val _ = headersLike.remove(headers, ZipkinB3SingleHeader)

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

    // first try getting flags/sampled info from b3 single header
    caseInsensitiveGet(headers, ZipkinB3SingleHeader) match {
      case Some(v) => {
        val (traceId, sampled, flags) = getFromB3SingleHeader(v)
        flags match {
          case Flags(1) => samplerTrue // flags debug
          case _ => { // flags invalid or not present, look for sampled field
            sampled match {
              case Some(true) => samplerTrue
              case Some(false) => samplerFalse
              case _ => samplerNone
            }
          }
        }
      }
      case _ => {
        // fallback to getting flags/sampled info from x-b3 headers in case b3 not present
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
    }
  }

  private def caseInsensitiveGet[H: HeadersLike](headers: H, key: String): Option[String] = {
    val headersLike = implicitly[HeadersLike[H]]
    headersLike.iterator(headers).collectFirst { case (k, v) if key.equalsIgnoreCase(k) => v }
  }
}
