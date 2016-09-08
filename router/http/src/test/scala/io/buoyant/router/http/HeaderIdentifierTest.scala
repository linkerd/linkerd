package io.buoyant.router.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class HeaderIdentifierTest extends FunSuite with Awaits with Exceptions {

  test("use path from header") {
    val identifier = HeaderIdentifier(Path.Utf8("http"), "my-header")
    val req = Request()
    req.headerMap.set("my-header", "/a/b/c")
    assert(await(identifier(req))._1 == Dst.Path(Path.read("/http/a/b/c")))
  }

  test("use path segment from header") {
    val identifier = HeaderIdentifier(Path.Utf8("http"), "my-header")
    val req = Request()
    req.headerMap.set("my-header", "hello")
    assert(await(identifier(req))._1 == Dst.Path(Path.read("/http/hello")))
  }

  test("header is absent") {
    val identifier = HeaderIdentifier(Path.Utf8("http"), "my-header")
    val req = Request()
    assertThrows[IllegalArgumentException] {
      await(identifier(req))
    }
  }
}
