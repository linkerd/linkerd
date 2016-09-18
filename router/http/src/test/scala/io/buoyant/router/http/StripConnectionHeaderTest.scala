package io.buoyant.router.http

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, ServiceFactory}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class StripConnectionHeaderTest extends FunSuite with Awaits {
  val sf = StripConnectionHeader.module.make(ServiceFactory.const(
    Service.mk[Request, Response] { req =>
      Future.value(Response())
    }
  ))

  test("strips 'Connection' header") {
    val req = Request()
    req.headerMap.set("Connection", "close")
    service(req)
    assert(req.headerMap.get("Connection") == None)
  }

  test("strips all headers listed in 'Connection' header") {
    val req = Request()
    req.headerMap.set("Connection", "Keep-Alive, Foo, Bar")
    req.headerMap.set("Keep-Alive", "timeout=30")
    req.headerMap.set("Foo", "abc")
    req.headerMap.set("Bar", "def")

    service(req)
    assert(req.headerMap.get("Connection") == None)
    assert(req.headerMap.get("Keep-Alive") == None)
    assert(req.headerMap.get("Foo") == None)
    assert(req.headerMap.get("Bar") == None)
  }

  def service(req: Request): Response = {
    val svc = await(sf())
    await(svc(req))
  }
}
