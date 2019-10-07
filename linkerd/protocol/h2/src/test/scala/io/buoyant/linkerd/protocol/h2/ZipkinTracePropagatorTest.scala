package io.buoyant.linkerd.protocol.h2

import org.scalatest.FunSuite
import com.twitter.finagle.buoyant.h2.{Headers, Request}
import com.twitter.finagle.buoyant.h2._
import io.buoyant.router.RoutingFactory._
import io.buoyant.linkerd.protocol.http._
import io.buoyant.router.H2Instances._

class ZipkinTracePropagatorTest extends FunSuite {
  test("get traceid from multi x-b3 headers, 64bit trace id, sampled, UPPER CASE header doesn't matter") {
    val ztp = new ZipkinTracePropagator

    //x-b3 multi headers with lower case
    val req1 = Request("http", Method.Get, "auf", "/", Stream.empty())
    req1.headers.add("x-b3-traceid", "80f198ee56343ba8")
    req1.headers.add("x-b3-spanid", "05e3ac9a4f6e3b90")
    req1.headers.add("x-b3-sampled", "1")
    req1.headers.add("x-b3-parentspanid", "e457b5a2e4d86bd1")

    //X-B3 multi heders with upper case
    val req2 = Request("http", Method.Get, "auf", "/", Stream.empty())
    req2.headers.add("X-B3-TRACEID", "80f198ee56343ba8")
    req2.headers.add("X-B3-SPANID", "05e3ac9a4f6e3b90")
    req2.headers.add("X-B3-SAMPLED", "1")
    req2.headers.add("X-B3-PARENTSPANID", "e457b5a2e4d86bd1")

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

      // expect to get the right sampled value which is 0
      assert(ZipkinTrace.getSampler(req1.headers).contains(1.0f))
    }}

    // expect to get the right sampled value which is 1
    assert(ZipkinTrace.getSampler(req1.headers).contains(1.0f))
    // expect to get the right sampled value which is 1
    assert(ZipkinTrace.getSampler(req2.headers).contains(1.0f))
  }

  test("get traceid from multi x-b3 headers - set/get 128bit trace, two fields") {
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("x-b3-traceid", "80f198ee56343ba864fe8b2a57d3eff7")
    req.headers.add("x-b3-spanid", "05e3ac9a4f6e3b90")

    val trace = ztp.traceId(req)
    assert(trace.isDefined) //expect trace exists
    trace.foreach { tid => {
      assert(tid.traceId.toString().equals("64fe8b2a57d3eff7"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.traceIdHigh.toString().contains("80f198ee56343ba8)"))

      val req2 = Request("http", Method.Get, "auf", "/", Stream.empty())
      ztp.setContext(req2, tid)
      assert(req2.headers.get("x-b3-traceid").contains("80f198ee56343ba864fe8b2a57d3eff7"))
      assert(req2.headers.get("x-b3-spanid").contains("05e3ac9a4f6e3b90"))
    }}
  }

  test("multi x-b3 headers - get flags/sampled test") {
    val ztp = new ZipkinTracePropagator()
    val req = Request("http", Method.Get, "auf", "/", Stream.empty())
    req.headers.add("x-b3-traceid", "80f198ee56343ba864fe8b2a57d3eff7")
    req.headers.add("x-b3-spanid", "05e3ac9a4f6e3b90")

    //flags 1, no sampled => sampler 1
    req.headers.add("x-b3-flags", "1")
    assert(ZipkinTrace.getSampler(req.headers).contains(1.0f))

    //flags 0 (invalid value), no sampled => sampler None
    req.headers.remove("x-b3-flags")
    req.headers.add("x-b3-flags", "0")
    assert(ZipkinTrace.getSampler(req.headers).contains(0.0f))

    //flags asd (invalid value), no sampled = > sampler None
    req.headers.remove("x-b3-flags")
    req.headers.add("x-b3-flags", "asd")
    assert(ZipkinTrace.getSampler(req.headers).isEmpty)

    //flags 1, sampled 1 (redundant sampled since flags is already 1)
    req.headers.remove("x-b3-flags")
    req.headers.add("x-b3-flags", "1")
    req.headers.add("x-b3-sampled", "1")
    assert(ZipkinTrace.getSampler(req.headers).contains(1.0f))

    //sampled 1, no flags
    req.headers.remove("x-b3-flags")
    req.headers.remove("x-b3-sampled")
    req.headers.add("x-b3-sampled", "1")
    assert(ZipkinTrace.getSampler(req.headers).contains(1.0f))

    //sampled 0, no flags
    req.headers.remove("x-b3-flags")
    req.headers.remove("x-b3-sampled")
    req.headers.add("x-b3-sampled", "0")
    assert(ZipkinTrace.getSampler(req.headers).contains(0.0f))

    //no sampled, no flags
    req.headers.remove("x-b3-flags")
    req.headers.remove("x-b3-sampled")
    assert(ZipkinTrace.getSampler(req.headers).isEmpty)
  }
}
