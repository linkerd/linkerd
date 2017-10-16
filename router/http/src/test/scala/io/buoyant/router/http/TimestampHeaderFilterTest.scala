package io.buoyant.router.http

import com.twitter.finagle.{Service, Stack}
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.util.{Future, Time}
import io.buoyant.test.FunSuite
import org.scalatest.OptionValues

class TimestampHeaderFilterTest extends FunSuite with OptionValues {

  val Ok = Service.mk[Request, Response] { req =>
    Future.value(Response())
  }

  test("adds timestamp header") {
    val svc = TimestampHeaderFilter
      .filter(TimestampHeaderFilter.DefaultHeader)
      .andThen(Ok)

    val req = Request()
    req.method = Method.Head
    req.uri = ""

    val rsp = await(svc(req))
    assert(rsp.headerMap.contains(TimestampHeaderFilter.DefaultHeader))

  }

  test("timestamp header contains request time") {
    val svc = TimestampHeaderFilter
      .filter(TimestampHeaderFilter.DefaultHeader)
      .andThen(Ok)

    val req = Request()
    req.method = Method.Head
    req.uri = ""

    val t_0 = Time.now
    val rspF = Time.withTimeAt(t_0) { _ =>
      svc(req)
    }
    val rsp = await(rspF)
    assert(Time.now != t_0)
    assert(
      rsp.headerMap
        .get(TimestampHeaderFilter.DefaultHeader)
        .value == t_0.inMillis.toString
    )
  }

}
