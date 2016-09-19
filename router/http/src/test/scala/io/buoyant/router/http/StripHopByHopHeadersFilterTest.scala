package io.buoyant.router.http

import com.twitter.finagle.Service
import com.twitter.finagle.http.Fields._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.router.http.StripHopByHopHeadersFilter.HopByHopHeaders
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class StripHopByHopHeadersFilterTest extends FunSuite with Awaits {
  val HopByHopHeaders = Seq(
    Connection,
    ProxyAuthenticate,
    ProxyAuthorization,
    Te,
    Trailer,
    TransferEncoding,
    Upgrade
  )

  val Ok = Service.mk[Request, Response] { req =>
    Future.value(Response())
  }

  val service = StripHopByHopHeadersFilter.filter andThen Ok

  test("strips all hop-by-hop headers from request") {
    val req = Request()
    HopByHopHeaders.foreach(req.headerMap.set(_, "Some Value"))
    await(service(req))
    assert(req.headerMap.isEmpty)
  }

  test("strips all hop-by-hop headers from response") {
    val nextService = Service.mk[Request, Response] { req =>
      val resp: Response = Response()
      HopByHopHeaders.foreach(resp.headerMap.set(_, "Some Value"))
      Future.value(resp)
    }

    val filter = StripHopByHopHeadersFilter.filter andThen nextService
    val resp = await(filter(Request()))

    assert(resp.headerMap.isEmpty)
  }

  test("strips all headers listed in 'Connection' header") {
    val req = Request()
    req.headerMap.set("Connection", "Keep-Alive, Foo, Bar")
    req.headerMap.set("Keep-Alive", "timeout=30")
    req.headerMap.set("Foo", "abc")
    req.headerMap.set("Bar", "def")

    await(service(req))

    assert(req.headerMap.isEmpty)
  }
}
