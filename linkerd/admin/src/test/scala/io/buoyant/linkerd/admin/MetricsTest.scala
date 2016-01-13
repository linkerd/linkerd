package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Status}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class MetricsHandlerTest extends FunSuite with Awaits {
  test("serves ok on /metrics") {
    val rsp = await(MetricsHandler(Request("/")))
    assert(rsp.status == Status.Ok)
  }
}
