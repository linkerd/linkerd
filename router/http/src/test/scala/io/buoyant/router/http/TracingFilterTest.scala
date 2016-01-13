package io.buoyant.router.http

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, Trace}
import com.twitter.util.{Future, Promise}
import io.buoyant.test.Awaits
import java.net.SocketAddress
import org.scalatest.FunSuite

class TracingFilterTest extends FunSuite with Awaits {

  test("tracing filter") {
    val tracer = new BufferingTracer

    val done = new Promise[Unit]
    val service = TracingFilter andThen Service.mk[Request, Response] { req =>
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

    Trace.letTracer(tracer) {
      val f = service(req)

      val reqEvents = tracer.iterator.toSeq
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.version", "HTTP/1.1")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.method", "HEAD")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.uri", "/foo?bar=bah")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.host", "monkeys")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.content-type", "text/plain")))

      tracer.clear()
      done.setDone()

      val rspEvents = tracer.iterator.toSeq
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.version", "HTTP/1.1")))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.status", Status.PaymentRequired.code)))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.content-type", "application/json")))
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("http.content-length", 304374)))
    }
  }
}
