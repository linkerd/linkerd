package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.test.FunSuite

class ApplyHostForwardedHeaderTest extends FunSuite {

  private val OkSvc = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.contentString = req.host getOrElse ""
    Future.value(rsp)
  }

  def mkReq() = Request()
  def service(req: Request = mkReq()) = {
    val svc = ApplyHostForwardedHeader.filter.andThen(OkSvc)
    svc(req)
  }

  test("Doesn't fail if neither host or forwarded headers are available") {
    val rsp = await(service(mkReq()))
    assert(rsp.contentString == "")
  }

  test("If Forwarded is not present host should not change") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    val rsp = await(service(req))
    assert(rsp.contentString == "buoyant.pizza")
  }

  test("If Forwarded is present but without host element, request's host should not change") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    req.headerMap.add("Forwarded", "nothost=8.8.4.4;key=value")
    val rsp = await(service(req))
    assert(rsp.contentString == "buoyant.pizza")
  }

  test("Host is changed if Forwarded header comes with host element") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    req.headerMap.add("Forwarded", "host=8.8.4.4")
    val rsp = await(service(req))
    assert(rsp.contentString == "8.8.4.4")
  }

  test("Host is changed if Forwarded header comes with host element and others") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    req.headerMap.add("Forwarded", "value=key,host=8.8.4.4 ;key=value;")
    val rsp = await(service(req))
    assert(rsp.contentString == "8.8.4.4")
  }

  test("First host is used if Forwarded header comes with multiple host elements and others") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    req.headerMap.add("Forwarded", "value=key,host=8.8.4.4 ;key=value,host=notthisone;")
    val rsp = await(service(req))
    assert(rsp.contentString == "8.8.4.4")
  }

  test("Host is changed if Forwarded header comes with host element and others, with spaces and empty elements") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    req.headerMap.add("Forwarded", "value=key; ; host='8.8.4.4' ; key=value;;")
    val rsp = await(service(req))
    assert(rsp.contentString == "8.8.4.4")
  }

  test("Host is changed if Forwarded header comes with host element and others, is not case sensitive") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    req.headerMap.add("forwarded", "value=key;HosT=\"mockbin.org\",key=value")
    val rsp = await(service(req))
    assert(rsp.contentString == "mockbin.org")
  }

}
