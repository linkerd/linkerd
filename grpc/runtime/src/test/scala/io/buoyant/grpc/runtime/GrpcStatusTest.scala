package io.buoyant.grpc.runtime

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.buoyant.h2
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.test.FunSuite

class GrcpStatusTest extends FunSuite {
  test("null message doesn't break trailers") {
    val s = GrpcStatus.Ok(null)
    val t = s.toTrailers
    assert(t.contains("grpc-status"))
    assert(!t.contains("grpc-message"))
  }

  test("empty message doesn't break trailers") {
    val s = GrpcStatus.Ok(null)
    val t = s.toTrailers
    assert(t.contains("grpc-status"))
    assert(!t.contains("grpc-message"))
  }

  test("message included in trailers") {
    val s = GrpcStatus.Ok("just a message")
    val t = s.toTrailers
    assert(t.contains("grpc-status"))
    assert(t.contains("grpc-message"))
  }
}
