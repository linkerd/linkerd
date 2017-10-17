package io.buoyant.router.http

import com.twitter.finagle.{Service, Stack}
import com.twitter.finagle.http.{HeaderMap, Method, Request, Response}
import com.twitter.util.{Future, Time}
import io.buoyant.test.FunSuite
import org.scalatest.OptionValues

class TimestampHeaderFilterTest extends FunSuite with OptionValues {

  val Header = "X-Request-Start"

  test("adds timestamp header") {
    var headers: Option[HeaderMap] = None
    val downstream = Service.mk[Request, Response] { req =>
      headers = Some(req.headerMap)
      Future.value(Response())
    }
    val svc = TimestampHeaderFilter
      .filter(Header)
      .andThen(downstream)

    val req = Request()
    req.method = Method.Head
    req.uri = ""

    val rsp = await(svc(req))
    assert(headers.value.contains(Header))

  }

  test("timestamp header contains request time") {
    var headers: Option[HeaderMap] = None
    val downstream = Service.mk[Request, Response] { req =>
      headers = Some(req.headerMap)
      Future.value(Response())
    }
    val svc = TimestampHeaderFilter
      .filter(Header)
      .andThen(downstream)

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
      headers.value
        .get(Header)
        .value == t_0.inMillis.toString
    )
  }

}
