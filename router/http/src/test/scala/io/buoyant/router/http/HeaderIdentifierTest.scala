package io.buoyant.router.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import io.buoyant.router.RoutingFactory.{UnidentifiedRequest, IdentifiedRequest}
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class HeaderIdentifierTest extends FunSuite with Awaits with Exceptions {

  test("use path from header") {
    val identifier = HeaderIdentifier(Path.Utf8("http"), "my-header", headerPath = true)
    val req = Request()
    req.headerMap.set("my-header", "/a/b/c")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/http/a/b/c"))
    )
  }

  test("header is absent") {
    val identifier = HeaderIdentifier(Path.Utf8("http"), "my-header", headerPath = true)
    val req = Request()
    assert(await(identifier(req)).isInstanceOf[UnidentifiedRequest[Request]])
  }

  test("header segment") {
    val identifier = HeaderIdentifier(Path.Utf8("http"), "my-header", headerPath = false)
    val req = Request()
    req.headerMap.set("my-header", "foo")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/http/foo"))
    )
  }
}
