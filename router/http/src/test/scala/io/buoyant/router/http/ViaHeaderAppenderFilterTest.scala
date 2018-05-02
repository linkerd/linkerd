package io.buoyant.router.http

import com.twitter.finagle.Service
import com.twitter.finagle.http.Fields.Via
import com.twitter.finagle.http.Version.{Http10, Http11}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ViaHeaderAppenderFilterTest extends FunSuite with Awaits {

  val Ok = Service.mk[Request, Response] { req =>
    assert(req.version == Http11)
    assert(req.host.nonEmpty)
    Future.value(Response())
  }

  val Ok10 = Service.mk[Request, Response] { req =>
    assert(req.version == Http10)
    val rsp = Response()
    rsp.version = Http10
    Future.value(rsp)
  }

  val service = ViaHeaderAppenderFilter.filter andThen Ok
  val service10 = ViaHeaderAppenderFilter.filter andThen Ok10

  test("adds via header to the request if none exists") {
    val req = Request()
    await(service(req))
    assert(req.headerMap("Via") == "1.1 linkerd")
  }

  test("adds via header to the HTTP 1.0 request if none exists") {
    val req = Request()
    req.version = Http10
    await(service10(req))
    assert(req.headerMap("Via") == "1.0 linkerd")
  }

  test("appends via header to the request if one already exists") {
    val req = Request()
    req.headerMap.set(Via, "1.0 bob, 1.1 mary")
    await(service(req))
    assert(req.headerMap("Via") == "1.0 bob, 1.1 mary, 1.1 linkerd")
  }

  test("adds via header to the response if none exists") {
    val req = Request()
    val resp = await(service(req))
    assert(resp.headerMap("Via") == "1.1 linkerd")
  }

  test("adds via header to the HTTP 1.0 response if none exists") {
    val req = Request()
    req.version = Http10
    val resp = await(service10(req))
    assert(resp.headerMap("Via") == "1.0 linkerd")
  }

  test("appends via header to the response if one already exists") {
    val nextService = Service.mk[Request, Response] { req =>
      val resp: Response = Response()
      resp.headerMap.set(Via, "1.0 bob, 1.1 mary")
      Future.value(resp)
    }

    val filter = ViaHeaderAppenderFilter.filter andThen nextService
    val resp = await(filter(Request()))

    assert(resp.headerMap("Via") == "1.0 bob, 1.1 mary, 1.1 linkerd")
  }
}
