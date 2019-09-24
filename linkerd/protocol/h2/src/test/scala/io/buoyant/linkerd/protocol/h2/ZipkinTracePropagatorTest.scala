package io.buoyant.linkerd.protocol.h2

import org.scalatest.FunSuite

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.buoyant.h2.{Headers, LinkerdHeaders, Request}
import com.twitter.finagle.http.util.StringUtil
import com.twitter.finagle.tracing.{Flags, SpanId, TraceId, TraceId128}
import com.twitter.util.Try
import io.buoyant.linkerd.{TracePropagator, TracePropagatorInitializer}

import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ZipkinTracePropagatorTest extends FunSuite {

  test("get traceid from a b3 single header - empty header") {
    // b3:
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "")

    val trace = ztp.traceId(req)
    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)
    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
  }

  test("get traceid from a b3 single header - one field - don't sample - b3: 0") {
    //b3: 0
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "0")

    val trace = ztp.traceId(req)

    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)

    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-sampled" has been added to request
    assert(req.headers.contains("x-b3-sampled"))

    // expect to get the right sampled value which is 0
    val sampler = ZipkinTrace.getSampler(req.headers)
    assert(sampler.contains(0.0f))

    //even after "x-b3-sampled" has been added to request this should not return a traceId because there's no span
    val trace2 = ztp.traceId(req)
    assert(trace2.isEmpty)
  }

  test("get traceid from a b3 single header - one field - sampled - b3: 1") {
    //b3: 1
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "1")

    val trace = ztp.traceId(req)

    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)

    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-sampled" has been added to request
    assert(req.headers.contains("x-b3-sampled"))


    // expect to get the right sampled value which is 1
    // this also checks "x-b3-sampled" has been added to request
    val sampler = ZipkinTrace.getSampler(req.headers)
    assert(sampler.contains(1.0f))

    //even after "x-b3-sampled" has been added to request this should not return a traceId because there's no span
    val trace2 = ztp.traceId(req)
    assert(trace2.isEmpty)
  }

  test("get traceid from a b3 single header - one field - debug - b3: d") {
    //b3: d
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "d")

    val trace = ztp.traceId(req)

    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)

    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-flags" has been added to request
    assert(req.headers.contains("x-b3-flags"))
    assert(req.headers.get("x-b3-flags").contains("1"))

    //even after "x-b3-flags" has been added to request this should not return a traceId because there's no span
    val trace2 = ztp.traceId(req)
    assert(trace2.isEmpty)
  }

  test("get traceid from a b3 single header - two fields - not yet sampled root span") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7")

    val trace = ztp.traceId(req)
    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-traceid" and "x-b3-spanid" have been added to request
    assert(req.headers.contains("x-b3-traceid") && req.headers.contains("x-b3-spanid"))

    trace match {
      case Some(tid) =>
        assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
        assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
      case None =>
        assert(false)  // "traceId does not exist"
    }

    // after "x-b3-" have been added check they have expected values
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - three fields - sampled root span") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7-1
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-1")

    val trace = ztp.traceId(req)
    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-sampled" have been added to request
    assert(req.headers.contains("x-b3-traceid") && req.headers.contains("x-b3-spanid") && req.headers.contains("x-b3-sampled"))

    trace match {
      case Some(tid) =>
        assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
        assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
        assert(tid.sampled.contains(true))
      case None =>
        assert(false)  // "traceId does not exist"
    }

    // after "x-b3-" have been added check they have the same values as above
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)

    //process sample
  }

  test("get traceid from a b3 single header - four fields - child span on debug") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7-d-5b4185666d50f68b

    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-d-5b4185666d50f68b")

    val trace = ztp.traceId(req)
    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-flags" and "x-b3-parentspanid" have been added to request
    assert(req.headers.contains("x-b3-traceid") &&req.headers.contains("x-b3-spanid") && req.headers.contains("x-b3-flags") && req.headers.contains("x-b3-parentspanid"))

    trace match {
      case Some(tid) =>
        assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
        assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
        assert(tid.parentId.toString().equals("5b4185666d50f68b"))
        assert(tid.flags.toLong == 1)
      case None =>
        assert(false)  // "traceId does not exist"
    }

    //test x-b3- headers have been added so we can get the same trace from them
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - four fields - not sampled child span") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7-d-5b4185666d50f68b

    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-0-5b4185666d50f68b")

    val trace = ztp.traceId(req)
    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-sampled" and "x-b3-parentspanid" have been added to request
    assert(req.headers.contains("x-b3-traceid") && req.headers.contains("x-b3-spanid") && req.headers.contains("x-b3-sampled") && req.headers.contains("x-b3-parentspanid"))

    trace match {
      case Some(tid) =>
        assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
        assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
        assert(tid.parentId.toString().equals("5b4185666d50f68b"))
        assert(tid.sampled.contains(false))
      case None =>
        assert(false)  // "traceId does not exist"
    }

    // expect to get the right sampled value which is 0
    // this also checks "x-b3-sampled" has been added to request
    val sampler = ZipkinTrace.getSampler(req.headers)
    assert(sampler.contains(0.0f))

    //test x-b3- headers have been added so we can get the same trace from them
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - 128bit trace, two fields") {
    //b3: 80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90")

    val trace = ztp.traceId(req)
    //b3 has been removed
    assert(req.headers.get("b3").isEmpty)
    // check "x-b3-traceid" and "x-b3-spanid" have been added to request
    assert(req.headers.contains("x-b3-traceid") && req.headers.contains("x-b3-spanid"))

    trace match {
      case Some(tid) =>
        assert(tid.traceId.toString().equals("64fe8b2a57d3eff7"))
        assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
        assert(tid.traceIdHigh.toString().equals("Some(80f198ee56343ba8)"))
      case None =>
        assert(false)  // "traceId does not exist"
    }

    // after "x-b3-" have been added check they have expected values
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }


  test("b3 single headers preferred over x-b3- multi headers") {
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-1")
    req.headers.add("x-b3-traceid", "0000000000000001")
    req.headers.add("x-b3-spanid", "0000000000000002")
    req.headers.add("x-b3-sampled", "0")

    val trace = ztp.traceId(req)
    trace match {
      case Some(tid) =>
        assert(tid.traceId.toString().equals("a3ce929d0e0e4736")) //expect traceid from b3 not from x-b3-traceid
        assert(tid.spanId.toString().equals("00f067aa0ba902b7")) // expect spanid from b3 not from x-b3-spanid
        assert(tid.sampled.contains(true)) // expect samplef from b3 not from x-b3-sampled
      case None =>
        assert(false)  // "traceId does not exist"
    }
  }

  test("same trace from b3 single headers and x-b3- multi headers, 128bit traceid, UPPER CASE header don't matter") {
    /* Turn on tracing and see if
      b3=80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90-1-e457b5a2e4d86bd1 results in the same context as:
      X-B3-TraceId: 80f198ee56343ba864fe8b2a57d3eff7
      X-B3-ParentSpanId: 05e3ac9a4f6e3b90
      X-B3-SpanId: e457b5a2e4d86bd1
      X-B3-Sampled: 1
     */

    //NOTE: This will also test case doesn't matter for b3 single or x-b3- multi headers
    val ztp = new ZipkinTracePropagator()
    val req1 = Request("http", Method.Get, "auf", "/", Stream.empty())
    req1.headers.add("B3", "80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90-1-e457b5a2e4d86bd1")

    val req2 = Request("http", Method.Get, "auf", "/", Stream.empty())
    req2.headers.add("X-B3-TRACEID", "80f198ee56343ba864fe8b2a57d3eff7")
    req2.headers.add("X-B3-SPANID", "05e3ac9a4f6e3b90")
    req2.headers.add("X-B3-SAMPLED", "1")
    req2.headers.add("X-B3-PARENTSPANID", "e457b5a2e4d86bd1")

    val trace1 = ztp.traceId(req1)
    val trace2 = ztp.traceId(req2)

    assert(trace1 == trace2)
  }
}