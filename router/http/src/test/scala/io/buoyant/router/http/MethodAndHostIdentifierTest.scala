package io.buoyant.router.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Method, Request, Version}
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, UnidentifiedRequest}
import io.buoyant.test.{Awaits, Exceptions}
import org.scalatest.FunSuite

class MethodAndHostIdentifierTest extends FunSuite with Awaits with Exceptions {

  test("http/1.1 request without a host header") {
    val identifier = MethodAndHostIdentifier(Path.Utf8("https"), false)
    val req = Request()
    req.uri = "/some/path?other=stuff"
    assert(await(identifier(req)).isInstanceOf[UnidentifiedRequest[Request]])
  }

  test("http/1.1 request with a host header") {
    val identifier = MethodAndHostIdentifier(Path.Utf8("https"), false)
    val req = Request()
    req.uri = "/some/path?other=stuff"
    req.host = "DoMaiN" // should be lowercased as per RFC 2616
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/https/1.1/GET/domain"))
    )
  }

  test("http/1.1 with URIs") {
    val identifier = MethodAndHostIdentifier(Path.Utf8("https"), true)
    val req = Request()
    req.uri = "/some/path?other=stuff"
    req.host = "domain"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/https/1.1/GET/domain/some/path"))
    )
  }

  test("http/1.0") {
    val identifier = MethodAndHostIdentifier(Path.Utf8("prefix"), false)
    val req = Request(Method.Post, "/drum/bass")
    req.version = Version.Http10
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/prefix/1.0/POST"))
    )
  }

  test("http/1.0 with uri") {
    val identifier = MethodAndHostIdentifier(Path.Utf8("prefix"), true)
    val req = Request(Method.Post, "/drum/bass")
    req.version = Version.Http10
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/prefix/1.0/POST/drum/bass"))
    )
  }
}
