package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Request}
import org.scalatest.FunSuite

import io.buoyant.linkerd.protocol.http._
import io.buoyant.router.HttpInstances._

class ZipkinTracePropagatorTest extends FunSuite {
  test("multi x-b3 headers, 64bit trace id, sampled, UPPER CASE header doesn't matter") {
    val ztp = new ZipkinTracePropagator

    //x-b3 multi headers with lower case
    val req1 = Request()
    req1.headerMap.add("x-b3-traceid", "80f198ee56343ba8")
    req1.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")
    req1.headerMap.add("x-b3-sampled", "1")
    req1.headerMap.add("x-b3-parentspanid", "e457b5a2e4d86bd1")

    //X-B3 multi heders with upper case
    val req2 = Request()
    req2.headerMap.add("X-B3-TRACEID", "80f198ee56343ba8")
    req2.headerMap.add("X-B3-SPANID", "05e3ac9a4f6e3b90")
    req2.headerMap.add("X-B3-SAMPLED", "1")
    req2.headerMap.add("X-B3-PARENTSPANID", "e457b5a2e4d86bd1")

    val trace1 = ztp.traceId(req1)
    val trace2 = ztp.traceId(req2)

    // traceid is the same, lower/upper case doesn't matter
    assert(trace1 == trace2)

    assert(trace1.isDefined) //expect trace exists
    trace1.foreach { tid => {
      assert(tid.traceId.toString().equals("80f198ee56343ba8"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.parentId.toString().equals("e457b5a2e4d86bd1"))
      assert(tid.sampled.contains(true))

      // expect to get the right sampled value which is 1
      assert(ZipkinTrace.getSampler(req1.headerMap).contains(1.0f))
    }}

    // expect to get the right sampled value which is 1
    assert(ZipkinTrace.getSampler(req1.headerMap).contains(1.0f))
    // expect to get the right sampled value which is 1
    assert(ZipkinTrace.getSampler(req2.headerMap).contains(1.0f))
  }

  test("multi x-b3 headers - set/get 128bit trace, two fields") {
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("x-b3-traceid", "80f198ee56343ba864fe8b2a57d3eff7")
    req.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")

    val trace = ztp.traceId(req)
    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("64fe8b2a57d3eff7"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.traceIdHigh.toString().contains("80f198ee56343ba8)"))

      val req2 = Request()
      ztp.setContext(req2, tid)
      assert(req2.headerMap.get("x-b3-traceid").contains("80f198ee56343ba864fe8b2a57d3eff7"))
      assert(req2.headerMap.get("x-b3-spanid").contains("05e3ac9a4f6e3b90"))
    }}
  }

  test("multi x-b3 headers - get flags/sampled test") {
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("x-b3-traceid", "80f198ee56343ba864fe8b2a57d3eff7")
    req.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")

    //flags 1, no sampled => sampler 1
    req.headerMap.add("x-b3-flags", "1")
    assert(ZipkinTrace.getSampler(req.headerMap).contains(1.0f))

    //flags 0 (invalid value), no sampled => sampler None
    req.headerMap.remove("x-b3-flags")
    req.headerMap.add("x-b3-flags", "0")
    assert(ZipkinTrace.getSampler(req.headerMap).isEmpty)

    //flags asd (invalid value), no sampled = > sampler None
    req.headerMap.remove("x-b3-flags")
    req.headerMap.add("x-b3-flags", "asd")
    assert(ZipkinTrace.getSampler(req.headerMap).isEmpty)

    //flags 1, sampled 1 (redundant sampled since flags is already 1)
    req.headerMap.remove("x-b3-flags")
    req.headerMap.add("x-b3-flags", "1")
    req.headerMap.add("x-b3-sampled", "1")
    assert(ZipkinTrace.getSampler(req.headerMap).contains(1.0f))

    //sampled 1, no flags
    req.headerMap.remove("x-b3-flags")
    req.headerMap.remove("x-b3-sampled")
    req.headerMap.add("x-b3-sampled", "1")
    assert(ZipkinTrace.getSampler(req.headerMap).contains(1.0f))

    //sampled 0, no flags
    req.headerMap.remove("x-b3-flags")
    req.headerMap.remove("x-b3-sampled")
    req.headerMap.add("x-b3-sampled", "0")
    assert(ZipkinTrace.getSampler(req.headerMap).contains(0.0f))

    //no sampled, no flags
    req.headerMap.remove("x-b3-flags")
    req.headerMap.remove("x-b3-sampled")
    assert(ZipkinTrace.getSampler(req.headerMap).isEmpty)
  }

  test("get traceid from a b3 single header - empty header") {
    // b3:
    val ztp = new ZipkinTracePropagator
    val req = Request()
    req.headerMap.add("b3", "")

    val trace = ztp.traceId(req)
    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    val sampler = ztp.sampler(req)
    // no sampler should be returned
    assert(sampler.isEmpty)
  }

  test("get traceid from a b3 single header - one field - don't sample - b3: 0") {
    //b3: 0
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "0")

    val trace = ztp.traceId(req)
    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    // expect to get the right sampled value which is 0
    val sampler = ZipkinTrace.getSampler(req.headerMap)
    assert(sampler.contains(0.0f))
  }

  test("get traceid from a b3 single header - one field - sampled - b3: 1") {
    //b3: 1
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "1")

    val trace = ztp.traceId(req)
    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    // expect to get the right sampled value which is 1
    val sampler = ZipkinTrace.getSampler(req.headerMap)
    assert(sampler.contains(1.0f))
  }

  test("get traceid from a b3 single header - one field - debug - b3: d") {
    //b3: d
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "d")

    val trace = ztp.traceId(req)
    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    // expect to get the right sampled value which is 1 when debug is set
    val sampler = ZipkinTrace.getSampler(req.headerMap)
    assert(sampler.contains(1.0f))
  }

  test("get traceid from a b3 single header - one field - invalid - b3: 2") {
    //b3: d
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "s")

    val trace = ztp.traceId(req)
    // this should not have returned a traceId because there's no span
    assert(trace.isEmpty)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    // expect to not get a sampler when sampling bit is invalid value (other than 0/1/d)
    val sampler = ZipkinTrace.getSampler(req.headerMap)
    assert(sampler.isEmpty)
  }

  test("get traceid from a b3 single header - two fields - not yet sampled root span") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7")

    val trace = ztp.traceId(req)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
      assert(tid.spanId.toString().equals("00f067aa0ba902b7"))

      // expect to not get a sampler when sampling bit is not set
      val sampler = ZipkinTrace.getSampler(req.headerMap)
      assert(sampler.isEmpty)

      ztp.setContext(req, tid)
      // check "b3" has been removed, "x-b3-traceid" and "x-b3-spanid" have been added to request
      assert(req.headerMap.get("b3").isEmpty)
      assert(Set("x-b3-traceid", "x-b3-spanid").subsetOf(req.headerMap.keys.toSet))
    }}

    // after "x-b3-" have been added check they have expected values
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - three fields - sampled root span") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7-1
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-1")

    val trace = ztp.traceId(req)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
      assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
      assert(tid.sampled.contains(true))

      // expect to get the right sampled value which is 1
      val sampler = ZipkinTrace.getSampler(req.headerMap)
      assert(sampler.contains(1.0f))

      ztp.setContext(req, tid)
      // check "b3" has been removed, "x-b3-traceid" and "x-b3-spanid" and "x-b3-sampledid" have been added to request
      assert(req.headerMap.get("b3").isEmpty)
      assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-sampled").subsetOf(req.headerMap.keys.toSet))
    }}

    // after "x-b3-" have been added check they have the same values as above
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - four fields - child span on debug") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7-d-5b4185666d50f68b

    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-d-5b4185666d50f68b")

    val trace = ztp.traceId(req)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
      assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
      assert(tid.parentId.toString().equals("5b4185666d50f68b"))
      assert(tid.flags.toLong == 1)

      // expect to get the right sampled value which is 1
      val sampler = ZipkinTrace.getSampler(req.headerMap)
      assert(sampler.contains(1.0f))

      ztp.setContext(req, tid)
      // check "b3" has been removed, "x-b3-traceid" and "x-b3-spanid" and "x-b3-flags" have been added to request
      assert(req.headerMap.get("b3").isEmpty)
      // check sampled not set when debug flag set
      assert(req.headerMap.get("x-b3-sampled").isEmpty)
      assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-flags").subsetOf(req.headerMap.keys.toSet))
    }}

    //test x-b3- headers have been added so we can get the same trace from them
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - four fields - not sampled child span") {
    //b3: a3ce929d0e0e4736-00f067aa0ba902b7-d-5b4185666d50f68b

    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-0-5b4185666d50f68b")

    val trace = ztp.traceId(req)
    //b3 has not been removed, other x-b3 have not been added
    assert(req.headerMap.keys == Set("b3"))

    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))
      assert(tid.spanId.toString().equals("00f067aa0ba902b7"))
      assert(tid.parentId.toString().equals("5b4185666d50f68b"))
      assert(tid.sampled.contains(false))

      // expect to get the right sampled value which is 0
      val sampler = ZipkinTrace.getSampler(req.headerMap)
      assert(sampler.contains(0.0f))

      ztp.setContext(req, tid)
      // check "b3" has been removed, "x-b3-traceid" and "x-b3-spanid" and "x-b3-parentspanid" have been added to request
      assert(req.headerMap.get("b3").isEmpty)
      // check sampled not set when debug flag set
      assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-parentspanid", "x-b3-sampled").subsetOf(req.headerMap.keys.toSet))
    }}

    //test x-b3- headers have been added so we can get the same trace from them
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }

  test("get traceid from a b3 single header - 128bit trace, two fields") {
    //b3: 80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90")

    val trace = ztp.traceId(req)
    //b3 has not been removed
    assert(!req.headerMap.get("b3").isEmpty)
    // check "x-b3-traceid" and "x-b3-spanid" have been added to request
    //assert(req.headerMap.keys == Set("x-b3-traceid", "x-b3-spanid"))

    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("64fe8b2a57d3eff7"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.traceIdHigh.toString().contains("80f198ee56343ba8)"))
    }}

    // after "x-b3-" have been added check they have expected values
    val trace2 = ztp.traceId(req)
    assert(trace == trace2)
  }


  test("b3 single headers preferred over x-b3- multi headers") {
    val ztp = new ZipkinTracePropagator()
    val req = Request()
    req.headerMap.add("b3", "a3ce929d0e0e4736-00f067aa0ba902b7-1")
    req.headerMap.add("x-b3-traceid", "0000000000000001")
    req.headerMap.add("x-b3-spanid", "0000000000000002")
    req.headerMap.add("x-b3-sampled", "0")

    val trace = ztp.traceId(req)
    assert(trace.isDefined)  //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("a3ce929d0e0e4736"))  //expect traceid from b3 not from x-b3-traceid)
      assert(tid.spanId.toString().equals("00f067aa0ba902b7")) // expect spanid from b3 not from x-b3-spanid
      assert(tid.sampled.contains(true)) // expect samplef from b3 not from x-b3-sampled
    }}
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
    val req1 = Request()
    req1.headerMap.add("B3", "80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90-1-e457b5a2e4d86bd1")

    val req2 = Request()
    req2.headerMap.add("X-B3-TRACEID", "80f198ee56343ba864fe8b2a57d3eff7")
    req2.headerMap.add("X-B3-SPANID", "05e3ac9a4f6e3b90")
    req2.headerMap.add("X-B3-SAMPLED", "1")
    req2.headerMap.add("X-B3-PARENTSPANID", "e457b5a2e4d86bd1")

    val trace1 = ztp.traceId(req1)
    val trace2 = ztp.traceId(req2)

    assert(trace1 == trace2)
  }

  test("cannot get trace from invalid b3 single header, too many fields") {
    val ztp = new ZipkinTracePropagator()
    val req1 = Request()
    req1.headerMap.add("B3", "80f198ee56343ba864fe8b2a57d3eff7-05e3ac9a4f6e3b90-1-e457b5a2e4d86bd1-extra1")

    assert(ztp.traceId(req1).isEmpty)
  }
}
