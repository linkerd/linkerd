package io.buoyant.linkerd.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.linkerd.{Build, Linker, NamerInitializers, TestNamer, TestProtocol, Yaml}
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class SummaryHandlerTest extends FunSuite with Awaits {

  // TODO: share with LinkerTest
  def parse(
    yaml: String
  ) = Linker.mk(
    TestProtocol.DefaultInitializers,
    NamerInitializers(new TestNamer)
  ).read(Yaml(yaml))

  val linker = parse("""
routers:
- protocol: plain
  servers:
  - port: 1
- protocol: fancy
  servers:
  - port: 2
""")
  val handler = new SummaryHandler(linker)

  test("serves ok on /") {
    val rsp = await(handler(Request("/")))
    assert(rsp.status == Status.Ok)
  }

  test("serves 404 other routes") {
    val rsp = await(handler(Request("/foo")))
    assert(rsp.status == Status.NotFound)
  }

  test("serves linkerd admin and version") {
    val rsp = await(handler(Request("/")))
    assert(rsp.contentString.contains("linkerd admin"))
    assert(rsp.contentString.contains(Build.load().version))
  }
}
