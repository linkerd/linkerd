package io.buoyant.router.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import io.buoyant.router.RoutingFactory.{UnidentifiedRequest, IdentifiedRequest}
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class PathIdentifierTest extends FunSuite with Awaits with Exceptions {

  test("request with same number of segments as requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc/subsvc"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/http/mysvc/subsvc"))
    )
  }

  test("request with more segments than requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc/subsvc/some/path?other=stuff"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/http/mysvc/subsvc"))
    )
  }

  test("request with fewer segments than requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc?other=stuff"
    assert(await(identifier(req)).isInstanceOf[UnidentifiedRequest[Request]])
  }

  test("consumes path segments") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2, consume = true)
    val req0 = Request()
    req0.uri = "/mysvc/subsvc/some/path?other=stuff"
    val identified = await(identifier(req0)).asInstanceOf[IdentifiedRequest[Request]]
    assert(identified.dst == Dst.Path(Path.read("/http/mysvc/subsvc")))
    assert(identified.request.uri == "/some/path?other=stuff")
  }

  test("consumes entire path") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2, consume = true)
    val req0 = Request()
    req0.uri = "/mysvc/subsvc"
    val identified = await(identifier(req0)).asInstanceOf[IdentifiedRequest[Request]]
    assert(identified.dst == Dst.Path(Path.read("/http/mysvc/subsvc")))
    assert(identified.request.uri == "/")
  }

  test("does not parse more segments than requested") {
    val identifier = PathIdentifier(Path.Utf8("http"), 2)
    val req = Request()
    req.uri = "/mysvc/subsvc/!"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/http/mysvc/subsvc"))
    )
  }

  test("preserve trailing slash with consume = true") {
    case class ExpParsedPath(segment: String, path: String)
    val testCaseConsume = Map(
      "/mysvc/subsvc/" -> ExpParsedPath("/http/mysvc/subsvc", "/"),
      "/mysvc/subsvc/path1/" -> ExpParsedPath("/http/mysvc/subsvc", "/path1/"),
      "/mysvc/subsvc/path1" -> ExpParsedPath("/http/mysvc/subsvc", "/path1"),
      "/mysvc/subsvc/path1/path2" -> ExpParsedPath("/http/mysvc/subsvc", "/path1/path2"),
      "/mysvc/subsvc/path1/path2/" -> ExpParsedPath("/http/mysvc/subsvc", "/path1/path2/"),
      "/mysvc/subsvc/path1/?foo=bar" -> ExpParsedPath("/http/mysvc/subsvc", "/path1/?foo=bar"),
      "/mysvc/subsvc/path1?foo=bar/" -> ExpParsedPath("/http/mysvc/subsvc", "/path1?foo=bar/")
    )
    val identifier = PathIdentifier(Path.Utf8("http"), 2, consume = true)
    testCaseConsume foreach ((input) => {
      val req0 = Request()
      req0.uri = input._1
      val identified = await(identifier(req0)).asInstanceOf[IdentifiedRequest[Request]]
      assert(identified.dst == Dst.Path(Path.read(input._2.segment)))
      assert(identified.request.uri == input._2.path)
    })
  }

  test("preserve trailing slash with consume = false") {
    case class ExpParsedPath(segment: String, path: String)
    val testCaseConsume = Map(
      "/mysvc/subsvc/" -> ExpParsedPath("/http/mysvc/subsvc", "/mysvc/subsvc/"),
      "/mysvc/subsvc/path1/" -> ExpParsedPath("/http/mysvc/subsvc", "/mysvc/subsvc/path1/"),
      "/mysvc/subsvc/path1" -> ExpParsedPath("/http/mysvc/subsvc", "/mysvc/subsvc/path1"),
      "/mysvc/subsvc/path1/path2" -> ExpParsedPath("/http/mysvc/subsvc", "/mysvc/subsvc/path1/path2"),
      "/mysvc/subsvc/path1/path2/" -> ExpParsedPath("/http/mysvc/subsvc", "/mysvc/subsvc/path1/path2/")
    )
    val identifier = PathIdentifier(Path.Utf8("http"), 2, consume = false)
    testCaseConsume foreach ((input) => {
      val req0 = Request()
      req0.uri = input._1
      val identified = await(identifier(req0)).asInstanceOf[IdentifiedRequest[Request]]
      assert(identified.dst == Dst.Path(Path.read(input._2.segment)))
      assert(identified.request.uri == input._2.path)
    })
  }
}
