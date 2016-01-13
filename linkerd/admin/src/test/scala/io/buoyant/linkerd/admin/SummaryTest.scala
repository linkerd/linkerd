package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.linkerd.Build
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class SummaryHandlerTest extends FunSuite with Awaits {
  test("serves ok on /") {
    val rsp = await(SummaryHandler(Request("/")))
    assert(rsp.status == Status.Ok)
  }

  test("serves 404 other routes") {
    val rsp = await(SummaryHandler(Request("/foo")))
    assert(rsp.status == Status.NotFound)
  }

  test("serves linkerd admin and version") {
    val rsp = await(SummaryHandler(Request("/")))
    assert(rsp.contentString.contains("linkerd admin"))
    assert(rsp.contentString.contains(Build.load().version))
  }
}
