package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Status, _}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class DiscardProxySideEffectsTest extends FunSuite with Awaits {

  def service: Service[Request, Response] = {
    val svc = Service.mk[Request, Response] { req =>
      val rsp = Response()
      rsp.status = Status.Ok
      rsp.setContentString(req.uri)
      rsp.headerMap ++= req.headerMap
      Future.value(rsp)
    }

    val stk = DiscardProxySideEffects.module.toStack(
      Stack.Leaf(DiscardProxySideEffects.module.role, ServiceFactory.const(svc))
    )

    await(stk.make(Stack.Params.empty)())
  }

  test("filter rewrites uri and drops Proxy-* headers for proxied requests") {
    val req = Request(Method.Get, "http://acme.co:8080/foo;matrix=uri;/bar?x=1&y=2")
    req.host = "example.com:9000"
    req.headerMap.set("Proxy-Connection", "Keep-Alive")
    req.headerMap.set("Proxy-Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    req.headerMap.set("Proxy-Authenticate", "Basic")
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.getContentString() == "/foo;matrix=uri;/bar?x=1&y=2")
    assert(
      result.headerMap.toSet == Set(
        ("Host", "acme.co:8080"),
        ("Pragma", "42")
      )
    )
  }

  test("filter drop Proxy-* Headers even for requests that doesn't look like proxied") {
    val req = Request(Method.Get, "/foo;matrix=uri;/bar?x=1&y=2")
    req.host = "acme.co:8080"
    req.headerMap.set("Proxy-Connection", "Keep-Alive")
    req.headerMap.set("Proxy-Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    req.headerMap.set("Proxy-Authenticate", "Basic")
    req.headerMap.set("Proxy-Foo", "Bar")
    req.headerMap.set("Pragma", "42")

    val result = await(service(req))
    assert(result.getContentString() == "/foo;matrix=uri;/bar?x=1&y=2")
    assert(
      result.headerMap.toSet == Set(
        ("Host", "acme.co:8080"),
        ("Pragma", "42")
      )
    )
  }
}
