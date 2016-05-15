package io.buoyant.router.http

import com.twitter.finagle.{Service, ServiceFactory}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class StripConnectionHeaderTest extends FunSuite with Awaits {

  test("strips 'Connection' header") {
    @volatile var connection: Option[Option[String]] = None
    val sf = StripConnectionHeader.module.make(ServiceFactory.const(
      Service.mk[Request, Response] { req =>
        connection = Some(req.headerMap.get("Connection"))
        Future.value(Response())
      }
    ))
    val svc = await(sf())
    val req = Request()
    req.headerMap.set("Connection", "close")
    val _ = await(svc(req))
    assert(connection == Some(None))
  }
}
