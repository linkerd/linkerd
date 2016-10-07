package io.buoyant.linkerd.protocol.h2

import com.twitter.io.Buf
import com.twitter.finagle.{Failure, Service}
import com.twitter.finagle.buoyant.h2._
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ErrorResponderTest extends FunSuite with Awaits {

  test("unknown dst") {
    val service = ErrorResponder.filter.andThen(Service.mk[Request, Response] { req =>
      Future.exception(RoutingFactory.UnknownDst(req, "yodles"))
    })

    val rsp = await(service(Request("http", "GET", "hihost", "/", Stream.Nil)))
    assert(rsp.status == Status.BadRequest)
    assert(rsp.headers.contains("l5d-err"))
    rsp.headers.get("l5d-err") match {
      case Seq(msg) => assert(msg.startsWith("Unknown destination: "))
      case msgs => fail(s"unexpected error header: $msgs")
    }
    rsp.data match {
      case Stream.Nil => fail("no body")
      case stream: Stream.Reader =>
        await(stream.read()) match {
          case _: Frame.Trailers =>
            fail("received trailers instead of data")
          case data: Frame.Data =>
            val Buf.Utf8(msg) = data.buf
            assert(msg.startsWith("Unknown destination: "))
        }
    }
  }

  test("general exception") {
    val service = ErrorResponder.filter.andThen(Service.mk[Request, Response] { _ =>
      Future.exception(Failure("yodles"))
    })

    val rsp = await(service(Request("http", "GET", "hihost", "/", Stream.Nil)))
    assert(rsp.status == Status.BadGateway)
    assert(rsp.headers.contains("l5d-err"))
    assert(rsp.headers.get("l5d-err") == Seq("yodles"))
    rsp.data match {
      case Stream.Nil => fail("no body")
      case stream: Stream.Reader =>
        await(stream.read()) match {
          case _: Frame.Trailers =>
            fail("received trailers instead of data")
          case data: Frame.Data =>
            assert(data.buf == Buf.Utf8("yodles"))
        }
    }
  }

  test("success") {
    val service = ErrorResponder.filter.andThen(Service.mk[Request, Response] { _ =>
      Future.value(Response(Status.Cowabunga, Stream.Nil))
    })

    val rsp = await(service(Request("http", "GET", "hihost", "/", Stream.Nil)))
    assert(rsp.status == Status.Cowabunga)
    assert(!rsp.headers.contains("l5d-err"))
    assert(rsp.data == Stream.Nil)
  }
}
