package io.buoyant.router.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Method, Request, Version}
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, UnidentifiedRequest}
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class HostIdentifierTest extends FunSuite with Awaits with Exceptions {

  test("http/1.1 request without a host header") {
    val identifier = HostIdentifier(Path.Utf8("https"))
    val req = Request()
    req.uri = "/some/path?other=stuff"
    assert(await(identifier(req)).isInstanceOf[UnidentifiedRequest[Request]])
  }

  test("http/1.1 request with a host header") {
    val identifier = HostIdentifier(Path.Utf8("https"))
    val req = Request()
    req.uri = "/some/path?other=stuff"
    req.host = "DoMaiN" // should be lowercased as per RFC 2616
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/https/1.1/domain"))
    )
  }

  test("http/1.0 req without host header") {
    val identifier = HostIdentifier(Path.Utf8("prefix"))
    val req = Request(Method.Post, "/drum/bass")
    req.version = Version.Http10
    assert(await(identifier(req)).isInstanceOf[UnidentifiedRequest[Request]])
  }

}
