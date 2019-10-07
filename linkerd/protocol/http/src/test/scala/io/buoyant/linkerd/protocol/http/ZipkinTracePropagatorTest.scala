package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.buoyant.Sampler
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.{Await, Future}
import org.scalatest.FunSuite
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Service
import com.twitter.finagle.tracing.{Trace}
import io.buoyant.linkerd.protocol.http._
import io.buoyant.router.HttpInstances._

class ZipkinTracePropagatorTest extends FunSuite {

  test("get traceid from multi x-b3 headers, 64bit trace id, sampled, UPPER CASE header doesn't matter") {
    val ztp = new ZipkinTracePropagator

    //x-b3 multi headers with lower case
    val req1 = Request()
    req1.headerMap.add("x-b3-traceid", "80f198ee56343ba8")
    req1.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")
    req1.headerMap.add("x-b3-sampled", "1")
    req1.headerMap.add("x-b3-parentspanid", "e457b5a2e4d86bd1")
    val trace1 = ztp.traceId(req1)
    // test multi x-b3 headers have been removed after traceId call
    assert(req1.headerMap.keys.isEmpty)

    //X-B3 multi heders with upper case
    val req2 = Request()
    req2.headerMap.add("X-B3-TRACEID", "80f198ee56343ba8")
    req2.headerMap.add("X-B3-SPANID", "05e3ac9a4f6e3b90")
    req2.headerMap.add("X-B3-SAMPLED", "1")
    req2.headerMap.add("X-B3-PARENTSPANID", "e457b5a2e4d86bd1")
    val trace2 = ztp.traceId(req2)
    // test multi x-b3 headers have been removed after traceId call
    assert(req2.headerMap.keys.isEmpty)

    // traceid is the same, lower/upper case headers don't matter
    assert(trace1 == trace2)

    assert(trace1.isDefined) //expect trace exists and has the expected values
    trace1.foreach { tid => {
      assert(tid.traceId.toString().equals("80f198ee56343ba8"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.parentId.toString().equals("e457b5a2e4d86bd1"))
      assert(tid.sampled.contains(true))
    }
    }

    val svc = new Service[Request, Response] {
      def apply(request: Request): Future[Response] = {
        // expect traceid to have been set on thread local context
        assert(Trace.idOption == trace1)

        // expect to get the right sampled value which is 1.0
        val sampler = ztp.sampler(req1)
        assert(sampler.contains(Sampler(1.0f)))

        Future.value(Response())
      }
    }

    val res = Trace.letIdOption(trace1) {
      svc(req1)
    }

    assert(Status.Ok == Await.result(res, 5.seconds).status)

    ztp.setContext(req1, trace1.get)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-sampled" and "x-b3-parentspanid" have been added to request
    assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-flags", "x-b3-sampled", "x-b3-parentspanid").subsetOf(req1.headerMap.keys.toSet))

  }

  test("get traceid from multi x-b3 headers, 64bit trace id, do not sample decision set") {
    val ztp = new ZipkinTracePropagator

    //x-b3 multi headers with lower case
    val req1 = Request()
    req1.headerMap.add("x-b3-traceid", "80f198ee56343ba8")
    req1.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")
    req1.headerMap.add("x-b3-sampled", "0")
    req1.headerMap.add("x-b3-parentspanid", "e457b5a2e4d86bd1")
    val trace1 = ztp.traceId(req1)
    // test multi x-b3 headers have been removed after traceId call
    assert(req1.headerMap.keys.isEmpty)

    assert(trace1.isDefined) //expect trace exists and has the expected values
    trace1.foreach { tid => {
      assert(tid.traceId.toString().equals("80f198ee56343ba8"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.parentId.toString().equals("e457b5a2e4d86bd1"))
      assert(tid.sampled.contains(false))
    }
    }

    val svc = new Service[Request, Response] {
      def apply(request: Request): Future[Response] = {
        // expect traceid to have been set on thread local context
        assert(Trace.idOption == trace1)

        // expect to get the right sampled value which is 1.0
        val sampler = ztp.sampler(req1)
        assert(sampler.contains(Sampler(0.0f)))

        Future.value(Response())
      }
    }

    val res = Trace.letIdOption(trace1) {
      svc(req1)
    }

    assert(Status.Ok == Await.result(res, 5.seconds).status)

    ztp.setContext(req1, trace1.get)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-sampled" and "x-b3-parentspanid" have been added to request
    assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-flags", "x-b3-sampled", "x-b3-parentspanid").subsetOf(req1.headerMap.keys.toSet))
  }

  test("get traceid from multi x-b3 headers, 64bit trace id, no sampling decision present") {
    val ztp = new ZipkinTracePropagator

    //x-b3 multi headers with lower case
    val req1 = Request()
    req1.headerMap.add("x-b3-traceid", "80f198ee56343ba8")
    req1.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")
    req1.headerMap.add("x-b3-parentspanid", "e457b5a2e4d86bd1")
    val trace1 = ztp.traceId(req1)
    // test multi x-b3 headers have been removed after traceId call
    assert(req1.headerMap.keys.isEmpty)

    assert(trace1.isDefined) //expect trace exists and has the expected values
    trace1.foreach { tid => {
      assert(tid.traceId.toString().equals("80f198ee56343ba8"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.parentId.toString().equals("e457b5a2e4d86bd1"))
      assert(tid.sampled.isEmpty)
    }
    }

    val svc = new Service[Request, Response] {
      def apply(request: Request): Future[Response] = {
        // expect traceid to have been set on thread local context
        assert(Trace.idOption == trace1)

        // expect to get the right sampled value which is 1.0
        val sampler = ztp.sampler(req1)
        assert(sampler.isEmpty)

        Future.value(Response())
      }
    }

    val res = Trace.letIdOption(trace1) {
      svc(req1)
    }

    assert(Status.Ok == Await.result(res, 5.seconds).status)

    ztp.setContext(req1, trace1.get)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-parentspanid" have been added to request
    assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-parentspanid").subsetOf(req1.headerMap.keys.toSet))
  }

  test("get traceid from multi x-b3 headers, 64bit trace id, debug flags set") {
    val ztp = new ZipkinTracePropagator

    //x-b3 multi headers with lower case
    val req1 = Request()
    req1.headerMap.add("x-b3-traceid", "80f198ee56343ba8")
    req1.headerMap.add("x-b3-spanid", "05e3ac9a4f6e3b90")
    req1.headerMap.add("x-b3-flags", "1")
    req1.headerMap.add("x-b3-parentspanid", "e457b5a2e4d86bd1")
    val trace1 = ztp.traceId(req1)
    // test multi x-b3 headers have been removed after traceId call
    assert(req1.headerMap.keys.isEmpty)

    assert(trace1.isDefined) //expect trace exists and has the expected values
    trace1.foreach { tid => {
      assert(tid.traceId.toString().equals("80f198ee56343ba8"))
      assert(tid.spanId.toString().equals("05e3ac9a4f6e3b90"))
      assert(tid.parentId.toString().equals("e457b5a2e4d86bd1"))
      assert(tid.sampled.contains(true))
    }
    }

    val svc = new Service[Request, Response] {
      def apply(request: Request): Future[Response] = {
        // expect traceid to have been set on thread local context
        assert(Trace.idOption == trace1)

        // expect to get the right sampled value which is 1.0
        val sampler = ztp.sampler(req1)
        assert(sampler.contains(Sampler(1.0f)))

        Future.value(Response())
      }
    }

    val res = Trace.letIdOption(trace1) {
      svc(req1)
    }

    assert(Status.Ok == Await.result(res, 5.seconds).status)

    ztp.setContext(req1, trace1.get)
    // check "x-b3-traceid" and "x-b3-spanid" and "x-b3-flags" and "x-b3-parentspanid" have been added to request
    assert(Set("x-b3-traceid", "x-b3-spanid", "x-b3-flags", "x-b3-parentspanid").subsetOf(req1.headerMap.keys.toSet))
  }

}
