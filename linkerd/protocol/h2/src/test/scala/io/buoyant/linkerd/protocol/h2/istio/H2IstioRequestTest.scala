package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.buoyant.h2.{Method, Request}
import org.scalatest.FunSuite

class H2IstioRequestTest extends FunSuite {
  test("generates istio request with expected attributes") {
    val httpRequest = Request("http", Method.Connect, "localhost", "/echo/123", null)
    val istioRequest = H2IstioRequest(httpRequest)

    assert(istioRequest.uri == httpRequest.path)
    assert(istioRequest.scheme == "http")
    assert(istioRequest.method == "CONNECT")
    assert(istioRequest.authority == "localhost")
  }
}
