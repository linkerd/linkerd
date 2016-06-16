package com.twitter.finagle.buoyant.linkerd

import com.twitter.finagle.Stack
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.tracing.{NullTracer, Trace}
import com.twitter.finagle.{Service, ServiceFactory, param}
import com.twitter.util.{Await, Future}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HttpTraceInitializerTest extends FunSuite with Awaits {

  // service that stuffs the current trace id into the response context so we can check it in tests
  def tracingService(): Service[Request, Response] = {
    val svc = Service.mk[Request, Response] { req =>
      val rsp = Response()
      Headers.Ctx.Trace.set(rsp.headerMap, Trace.id)
      Future.value(rsp)
    }
    val factory = HttpTraceInitializer.serverModule
      .toStack(Stack.Leaf(Stack.Role("ep"), ServiceFactory.const(svc)))
      .make(Stack.Params.empty + param.Tracer(NullTracer))
    Await.result(factory())
  }

  test("does not set sampled on trace when sample header is not set") {
    val service = tracingService()
    val req = Request()

    val rsp = await(service(req))
    assert(Headers.Ctx.Trace.get(rsp.headerMap).exists(_.sampled.isEmpty))
  }

  test("does not trace when sample header is set to 0") {
    val service = tracingService()
    val req = Request()
    req.headerMap(Headers.Sample.Key) = "0"

    val rsp = await(service(req))
    assert(Headers.Ctx.Trace.get(rsp.headerMap).exists(_.sampled.contains(false)))
  }

  test("traces when sample header is set to 1") {
    val service = tracingService()
    val req = Request()
    req.headerMap(Headers.Sample.Key) = "1"

    val rsp = await(service(req))
    assert(Headers.Ctx.Trace.get(rsp.headerMap).exists(_.sampled.contains(true)))
  }

  test("parent/child requests are assigned linked request ids") {
    val service = tracingService()
    val req1 = Request()
    val rsp1 = await(service(req1))
    val reqId1 = Headers.Ctx.Trace.get(rsp1.headerMap).get

    val req2 = Request()
    req2.headerMap(Headers.Ctx.Trace.Key) = rsp1.headerMap(Headers.Ctx.Trace.Key)
    val rsp2 = await(service(req2))
    val reqId2 = Headers.Ctx.Trace.get(rsp2.headerMap).get

    assert(reqId1.spanId == reqId1.parentId)
    assert(reqId2.spanId != reqId2.parentId)
    assert(reqId2.parentId == reqId1.spanId)
    assert(reqId1.traceId == reqId2.traceId)
  }

  test("independent requests are assigned different request ids") {
    val service = tracingService()
    val req = Request()

    val rsp1 = await(service(req))
    val reqId1 = Headers.Ctx.Trace.get(rsp1.headerMap).get
    val rsp2 = await(service(req))
    val reqId2 = Headers.Ctx.Trace.get(rsp2.headerMap).get

    assert(reqId1.traceId != reqId2.traceId)
    assert(reqId1.spanId != reqId2.spanId)
    assert(reqId1.parentId != reqId2.parentId)
  }
}
