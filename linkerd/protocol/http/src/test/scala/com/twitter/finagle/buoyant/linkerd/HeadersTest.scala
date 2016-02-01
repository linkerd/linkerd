package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.http.Request
import com.twitter.finagle.tracing.Trace
import org.scalatest.FunSuite

class HeadersTest extends FunSuite {

  test("round trip encoding for ctx header") {
    val orig = Trace.nextId
    val headers = Request().headerMap

    assert(!headers.contains(Headers.Ctx.Key))
    Headers.Ctx.set(headers, orig)
    assert(headers.contains(Headers.Ctx.Key))
    assert(Headers.Ctx.get(headers) == Some(orig))

    Headers.Ctx.clear(headers)
    assert(!headers.contains(Headers.Ctx.Key))
  }

  test("round trip encoding for request id header") {
    val orig = Trace.nextId
    val headers = Request().headerMap

    assert(!headers.contains(Headers.RequestId.Key))
    Headers.RequestId.set(headers, orig.traceId)
    assert(headers.contains(Headers.RequestId.Key))
    assert(headers.get(Headers.RequestId.Key) == Some(orig.traceId.toString))
  }

  test("only extract valid sample header values") {
    val headers = Request().headerMap
    assert(Headers.Sample.get(headers).isEmpty)

    headers(Headers.Sample.Key) = "yes"
    assert(Headers.Sample.get(headers).isEmpty)

    headers(Headers.Sample.Key) = "0"
    assert(Headers.Sample.get(headers).contains(0f))

    headers(Headers.Sample.Key) = "0.2"
    assert(Headers.Sample.get(headers).contains(0.2f))

    headers(Headers.Sample.Key) = "1"
    assert(Headers.Sample.get(headers).contains(1f))

    headers(Headers.Sample.Key) = "1.5"
    assert(Headers.Sample.get(headers).isEmpty)
  }
}
