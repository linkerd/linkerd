package io.buoyant.grpc.runtime

import com.google.protobuf.{CodedInputStream, CodedOutputStream}
import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, Status, Stream => H2Stream}
import com.twitter.util.{Await, Future}
import io.buoyant.grpc.runtime.ClientDispatcher.Rpc
import org.scalatest.{FunSuite, Matchers}

class UnaryToUnaryTest extends FunSuite with Matchers {
  test("trailers-only h2 response results in failed future with grpc status") {
    val expectedStatus = GrpcStatus.Internal("foo")
    val fakeService = Service.mk[Request, Response] { _ =>
      val headers = Headers(
        Headers.Status -> Status.Ok.code.toString,
        "grpc-status" -> expectedStatus.code.toString,
        "grpc-message" -> expectedStatus.message
      )
      val response = Response(headers, H2Stream.empty())
      Future.value(response)
    }
    val fakeCodec = new Codec[Unit] {
      override def decode: CodedInputStream => Unit = Function.const(())
      override def encode(t: Unit, pbos: CodedOutputStream): Unit = ()
      override def sizeOf(t: Unit): Int = 0
    }
    val unaryToUnary = Rpc.UnaryToUnary(fakeService, "/foo", fakeCodec, fakeCodec)
    val actualException = the [Throwable] thrownBy Await.result(unaryToUnary(()))

    actualException shouldBe expectedStatus
  }
}
