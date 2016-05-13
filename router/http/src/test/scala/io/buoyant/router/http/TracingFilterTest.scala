package io.buoyant.router.http

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, SpanId, Trace, TraceId}
import com.twitter.io.Buf
import com.twitter.util.{Future, Promise}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TracingFilterTest extends FunSuite with Awaits {

  test("tracing filter") {
    val tracer = new BufferingTracer

    val done = new Promise[Unit]
    val service = new TracingFilter andThen Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.status = Status.PaymentRequired
      rsp.contentType = "application/json"
      rsp.contentLength = 304374
      done before Future.value(rsp)
    }

    val req = Request()
    req.method = Method.Head
    req.uri = "/foo?bar=bah"
    req.host = "monkeys"
    req.contentType = "text/plain"
    req.contentLength = 94114

    Trace.letTracer(tracer) {
      val f = service(req)

      val reqEvents = tracer.iterator.toSeq
      assert(reqEvents.exists(_.annotation == Annotation.Rpc("HEAD")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.uri", "/foo?bar=bah")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.req.method", "HEAD")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.req.host", "monkeys")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.req.version", "HTTP/1.1")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.req.content-type", "text/plain")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.req.content-length", 94114)))

      tracer.clear()
      done.setDone()
      await(f)

      val rspEvents = tracer.iterator.toSeq
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.rsp.status", Status.PaymentRequired.code)))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.rsp.version", "HTTP/1.1")))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.rsp.content-type", "application/json")))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.rsp.content-length", 304374)))
    }
  }

  test("tracing filter only applies when tracing is not disabled") {
    val tracer = new BufferingTracer

    val service = new TracingFilter andThen Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.status = Status.Ok
      Future.value(rsp)
    }

    def req = Request(Method.Head, "")

    Trace.letTracerAndId(tracer, TraceId(None, None, SpanId(100L), Some(false))) {
      await(service(req))
      assert(tracer.iterator.isEmpty)
    }

    tracer.clear()
    Trace.letTracerAndId(tracer, TraceId(None, None, SpanId(101L), Some(true))) {
      await(service(req))
      assert(tracer.iterator.size == 6)
    }

    tracer.clear()
    Trace.letTracerAndId(tracer, TraceId(None, None, SpanId(102L), None)) {
      await(service(req))
      assert(tracer.iterator.size == 6)
    }
  }

  test("error message annotations") {
    val tracer = new BufferingTracer

    val service = new TracingFilter andThen Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.status = Status.InternalServerError
      rsp.contentType = "application/json"
      rsp.content = Buf.Utf8("There was an error processing your request")
      Future.value(rsp)
    }

    val req = Request()
    req.method = Method.Head
    req.uri = ""

    Trace.letTracer(tracer) {
      await(service(req))
      val rspEvents = tracer.iterator.toSeq
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.rsp.status", Status.InternalServerError.code)))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.rsp.body", Buf.Utf8("There was an error processing your request"))))
    }
  }

}
