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

  test("rewrite proxy requests as non-proxy requests") {
    val req = Request(Method.Get, "http://acme.co:8080/foo;matrix=uri;/bar?x=1&y=2&z=%28%20hi%20%29")
    req.host = "example.com:9000"
    req.headerMap.set("Proxy-Connection", "Keep-Alive")
    req.headerMap.set("Proxy-Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    req.headerMap.set("Proxy-Authenticate", "Basic")
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.getContentString() == "/foo;matrix=uri;/bar?x=1&y=2&z=(%20hi%20)")
    assert(result.headerMap == Map(
      "Host" -> "acme.co:8080",
      "Pragma" -> "42",
      "Proxy-Foo" -> "Bar"
    ))
  }

  test("strip Proxy-* Headers for non-proxy requests") {
    val req = Request(Method.Get, "/foo;matrix=uri;/bar?x=1&y=2")
    req.host = "acme.co:8080"
    req.headerMap.set("Proxy-Connection", "Keep-Alive")
    req.headerMap.set("Proxy-Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    req.headerMap.set("Proxy-Authenticate", "Basic")
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.getContentString() == "/foo;matrix=uri;/bar?x=1&y=2")
    assert(result.headerMap == Map(
      "Host" -> "acme.co:8080",
      "Pragma" -> "42",
      "Proxy-Foo" -> "Bar"
    ))
  }
}
