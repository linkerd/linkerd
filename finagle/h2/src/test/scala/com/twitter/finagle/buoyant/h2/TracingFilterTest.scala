package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Service
import com.twitter.finagle.tracing.{Annotation, BufferingTracer, Trace}
import com.twitter.util.{Future, Promise}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TracingFilterTest extends FunSuite with Awaits {

  test("tracing filter") {
    val tracer = new BufferingTracer

    val done = new Promise[Unit]
    val service = new TracingFilter andThen Service.mk[Request, Response] { req =>
      val rsp = Response(Status.PaymentRequired, Stream.empty())
      done before Future.value(rsp)
    }

    val req = Request("http", Method.Head, "monkeys", "/foo", Stream.empty())

    Trace.letTracer(tracer) {
      val f = service(req)

      val reqEvents = tracer.iterator.toSeq
      assert(reqEvents.exists(_.annotation == Annotation.Rpc("HEAD /foo")))
      assert(reqEvents.exists(_.annotation == Annotation.BinaryAnnotation("h2.req.authority", "monkeys")))

      tracer.clear()
      done.setDone()
      await(f)

      val rspEvents = tracer.iterator.toSeq
      assert(rspEvents.exists(_.annotation == Annotation.BinaryAnnotation("h2.rsp.status", Status.PaymentRequired.code)))
    }
  }
}
