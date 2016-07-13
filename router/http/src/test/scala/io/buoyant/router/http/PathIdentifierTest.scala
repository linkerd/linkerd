package io.buoyant.router.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class PathIdentifierTest extends FunSuite with Awaits with Exceptions {

  test("request with same number of segments as requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc/subsvc"
    assert(await(identifier(req))._1 == Dst.Path(Path.read("/http/mysvc/subsvc")))
  }

  test("request with more segments than requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc/subsvc/some/path?other=stuff"
    assert(await(identifier(req))._1 == Dst.Path(Path.read("/http/mysvc/subsvc")))
  }

  test("request with fewer segments than requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc?other=stuff"
    assertThrows[IllegalArgumentException] {
      await(identifier(req))
    }
  }

  test("consumes path segments") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2, consume = true)
    val req0 = Request()
    req0.uri = "/mysvc/subsvc/some/path?other=stuff"
    val (dst, req1) = await(identifier(req0))
    assert(dst == Dst.Path(Path.read("/http/mysvc/subsvc")))
    assert(req1.uri == "/some/path?other=stuff")
  }

  test("consumes entire path") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2, consume = true)
    val req0 = Request()
    req0.uri = "/mysvc/subsvc"
    val (dst, req1) = await(identifier(req0))
    assert(dst == Dst.Path(Path.read("/http/mysvc/subsvc")))
    assert(req1.uri == "/")
  }
}
