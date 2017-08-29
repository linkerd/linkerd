package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http.{Method, Request, Version}
import org.scalatest.FunSuite

class HttpIstioRequestTest extends FunSuite {
  test("generates istio request with expected attributes") {
    val httpRequest = Request(Version.Http11, Method.Options, "http://example.org:9090/echo")
    val istioRequest = HttpIstioRequest(httpRequest)

    assert(istioRequest.uri == httpRequest.path)
    assert(istioRequest.scheme == "")
    assert(istioRequest.method == "OPTIONS")
    assert(istioRequest.authority == "")
  }
}
