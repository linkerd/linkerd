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

  /**
   * The separate "b3" header in b3 single header format :
   * b3={x-b3-traceid}-{x-b3-spanid}-{if x-b3-flags 'd' else x-b3-sampled}-{x-b3-parentspanid},
   * where the last two fields are optional.
   */
  val ZipkinB3SingleHeader = "b3"

  /**
   * Handle b3 the same way as finagle handles b3, extract the traceid from "b3" header, write back "X-B3-*" multi
   * headers, and let the flow continue as if it has received "x-b3-".
   *
   * @note Copied/adapted from finagle TraceInfo.scala
   */
  /*  def convertB3SingleHeaderToMultiHeaders[H: HeadersLike](headers: H): Unit = {
    val headersLike = implicitly[HeadersLike[H]]

    val b3SingleHeader = caseInsensitiveGet(headers, ZipkinB3SingleHeader)
    if (b3SingleHeader.isDefined) {
      def matchSampledAndFlags(headers: H, value: String): Unit = {
        value match {
          case "0" =>
            headersLike.set(headers, ZipkinSampleHeader, "0")
          case "d" =>
            headersLike.set(headers, ZipkinFlagsHeader, "1")
          case "1" =>
            headersLike.set(headers, ZipkinSampleHeader, "1")
          case _ =>
            () // do not set anything on invalid value
        }
        ()
      }

      def handleTraceAndSpanIds(headers: H, a: String, b: String): Unit = {
        headersLike.set(headers, ZipkinTraceHeader, a)
        headersLike.set(headers, ZipkinSpanHeader, b)
        ()
      }

      b3SingleHeader.map(_.split("-")) match {
        case Some(a) =>
          a.size match {
            case 1 =>
              // either debug flag or sampled
              matchSampledAndFlags(headers, a(0))
            case 2 =>
              // this is required to be traceId, spanId
              handleTraceAndSpanIds(headers, a(0), a(1))
            case 3 =>
              handleTraceAndSpanIds(headers, a(0), a(1))
              matchSampledAndFlags(headers, a(2))
            case 4 =>
              handleTraceAndSpanIds(headers, a(0), a(1))
              matchSampledAndFlags(headers, a(2))
              headersLike.set(headers, ZipkinParentHeader, a(3))
            case _ =>
              // bogus, do not handle the case when b3 is empty or has more than 4 components
              ()
          }
        case None =>
          ()
      }
      val _ = headersLike.remove(headers, ZipkinB3SingleHeader)
    }
  }
*/

  /*
  def handleTraceAndSpanIds(headers: H, a: String, b: String): Unit = {
    headersLike.set(headers, ZipkinTraceHeader, a)
    headersLike.set(headers, ZipkinSpanHeader, b)
    ()
  }
*/
  def getFromB3SingleHeader[H: HeadersLike](b3SingleHeader: String): (Option[TraceId], Option[Boolean], Flags) = {
    /* translate the b3 single header "one character that describes sampling" to two values sampled and flags which closely
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
        // either debug flag or sampled
        val (sampled_, flags) = matchSampledAndFlags(sampled)
        (None, sampled_, flags)

      case traceId :: spanId :: Nil =>
        // expect to read a 128bit traceid field, b3 single header supports 128bit traceids
        val trace128Bit = TraceId128(traceId)
        val (sampled_, flags) = matchSampledAndFlags("")
        (SpanId.fromString(spanId).map(sid => TraceId(trace128Bit.low, None, sid, None, Flags(), trace128Bit.high)), sampled_, flags)

      case traceId :: spanId :: sampled :: Nil =>
        val (sampled_, flags) = matchSampledAndFlags(sampled)
        val trace128Bit = TraceId128(traceId)

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
   * There's no way to know what the downstream is capable of, and it is more likely it supports "X-B3-*" vs not, so,
   * the portable choice is to always write down "X-B3-"  even if we read "b3". Finagle does a similar thing in
   * finagle-http-base: read "b3", write back "X-B3-".
   *
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
  /*
    /*val b3SingleHeader = caseInsensitiveGet(headers, ZipkinB3SingleHeader)
    b3SingleHeader.foreach {
      s => getFromB3SingleHeader(s)
    }*/
    val headersLike = implicitly[HeadersLike[H]]
    if (headersLike.contains(headers, "b3") || headersLike.contains(headers, "B3"))
    val traceId = caseInsensitiveGet(headers, ZipkinB3SingleHeader).flatMap(getFromB3SingleHeader)
    getFromXB3MultiHeaders(headers)
    //getFromXB3MultiHeaders(headers)
    /*
    //if "b3" present convert it to "x-b3-" multio and let the flow continue as if it has received "x-b3-"
    val b3SingleHeader = caseInsensitiveGet(headers, ZipkinB3SingleHeader)
    if (b3SingleHeader.isDefined) {
      b3SingleHeader.map { s => getFromB3SingleHeader(s) }
    } else {
      val traceId = getFromXB3MultiHeaders(headers)
      traceId
    }*/
  }*/

  def set[H: HeadersLike](headers: H, id: TraceId): Unit = {
    val headersLike = implicitly[HeadersLike[H]]

    val _ = headersLike.remove(headers, ZipkinB3SingleHeader)

    headersLike.set(headers, ZipkinSpanHeader, id.spanId.toString)

    // support setting a 128bit traceid
    if (id.traceIdHigh.isEmpty) {
      headersLike.set(headers, ZipkinTraceHeader, id.traceId.toString)
    } else {
      headersLike.set(headers, ZipkinTraceHeader, id.traceIdHigh.get.toString + id.traceId.toString)
    }

    headersLike.set(headers, ZipkinParentHeader, id.parentId.toString)

    //the only valid value for x-b3-flags is 1, any other flag values are invalid and should be ignored
    if (id.flags.toLong == 1) {
      //when setting x-b3-flags to debug, 1, do not also set x-b3-sampled because it is a redundant info
      headersLike.set(headers, ZipkinFlagsHeader, id.flags.toLong.toString)
    } else {
      // sampled is set only when flags are not set and sampled data is present in traceId
      headersLike.set(headers, ZipkinSampleHeader, (id.sampled.map {
        case true => 1
        case false => 0
      }).toString)
    }

    ()
  }

  /**
   * This method will also work on requests containing "b3" single header, because after the header is read and
   * processed the "X-B3-Sampled" is written back onto request using the "Sampled" value found in the "b3" header.
   */
  def getSampler[H: HeadersLike](headers: H): Option[Float] = {
    val headersLike = implicitly[HeadersLike[H]]

    caseInsensitiveGet(headers, ZipkinB3SingleHeader) match {
      case Some(v) => {
        val (traceId, sampled, flags) = getFromB3SingleHeader(v)
        flags match {
          case Flags(1) => Some(1.0f)
          case _ => {
            sampled.map {
              case true => 1.0f
              case false => 0.0f
            }
          }
        }
      }
      case _ => {
        val flags = headersLike.get(headers, ZipkinFlagsHeader)
        if (flags.isEmpty) {
          headersLike.get(headers, ZipkinSampleHeader).flatMap { s =>
            Try(s.toFloat).toOption.map {
              case v if v < 0 => 0.0f
              case v if v > 1 => 1.0f
              case v => v
            }
          }
        } else {
          flags.map {
            case v if v.toLong == 1 => 1.0f
          }
        }
      }
    }

    /*
    val traceId = getFromB3SingleHeader(headers)
    if (traceId.isDefined) {
      traceId.map {
        case tid => tid._sampled match {
          case Some(false) => 0.0f
          case Some(true) => 1.0f
        }
      }
    } else { */

  }

  private def caseInsensitiveGet[H: HeadersLike](headers: H, key: String): Option[String] = {
    val headersLike = implicitly[HeadersLike[H]]
    headersLike.iterator(headers).collectFirst { case (k, v) if key.equalsIgnoreCase(k) => v }
  }
}
