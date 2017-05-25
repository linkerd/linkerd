package io.buoyant.router.h2

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2._
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class DupRequestTest extends FunSuite with Awaits {

  test("changes in service don't impact original") {
    val service = DupRequest.filter.andThen(Service.mk[Request, Response] { req =>
      req.headers.set("badness", "true")
      Future.value(Response(Status.Ok, Stream.empty()))
    })

    val req = Request("http", Method.Get, "hihost", "/", Stream.empty())
    await(service(req))
    assert(!req.headers.contains("badness"))
  }
}
