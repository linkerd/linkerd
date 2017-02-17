package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Status, _}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ProxyRewriteFilterTest extends FunSuite with Awaits {

  def service: Service[Request, Response] = {
    val svc = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.status = Status.Ok
      rsp.setContentString(req.uri)
      rsp.headerMap ++= req.headerMap
      Future.value(rsp)
    }

    val stk = ProxyRewriteFilter.module.toStack(
      Stack.Leaf(ProxyRewriteFilter.module.role, ServiceFactory.const(svc))
    )

    await(stk.make(Stack.Params.empty)())
  }

  test("rewrite host header for HTTP/1.1 requests") {
    val req = Request(Method.Get, "http://acme.co:8080/foo")
    req.host = "example.com:9000"
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.headerMap == Map(
      "Host" -> "acme.co:8080",
      "Pragma" -> "42",
      "Proxy-Foo" -> "Bar"
    ))
  }

  test("don't double URL-encode query parameter values") {
    val req = Request(Method.Get, "http://acme.co:8080/foo?text=hello%20world")
    req.host = "example.com:9000"
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.getContentString() == "/foo?text=hello%20world")
  }

  test("don't URL-decode url-encoded query parameter values") {
    val req = Request(Method.Get, "http://acme.co:8080/foo?x=R%26D")
    req.host = "example.com:9000"
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.getContentString() == "/foo?x=R%26D")
  }

}
