package com.twitter.finagle.buoyant.linkerd

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
      val rep = Response()
      Headers.Ctx.set(rep.headerMap, Trace.id)
      Future.value(rep)
    }
    val tracer = param.Tracer(NullTracer)
    val factory = HttpTraceInitializer.server.make(tracer, ServiceFactory.const(svc))
    Await.result(factory())
  }

  test("does not set sampled on trace when sample header is not set") {
    val service = tracingService()
    val req = Request()

    val rep = await(service(req))
    assert(Headers.Ctx.get(rep.headerMap).exists(_.sampled.isEmpty))
  }

  test("does not trace when sample header is set to 0") {
    val service = tracingService()
    val req = Request()
    req.headerMap(Headers.Sample.Key) = "0"

    val rep = await(service(req))
    assert(Headers.Ctx.get(rep.headerMap).exists(_.sampled.contains(false)))
  }

  test("traces when sample header is set to 1") {
    val service = tracingService()
    val req = Request()
    req.headerMap(Headers.Sample.Key) = "1"

    val rep = await(service(req))
    assert(Headers.Ctx.get(rep.headerMap).exists(_.sampled.contains(true)))
  }
}
