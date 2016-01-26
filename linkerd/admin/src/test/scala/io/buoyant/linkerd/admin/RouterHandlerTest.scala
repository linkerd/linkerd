package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Status, Request}
import io.buoyant.linkerd._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class RouterHandlerTest extends FunSuite with Awaits {

  // TODO: share with LinkerTest
  def parse(
    yaml: String
  ) = Linker.mk(
    TestProtocol.DefaultInitializers,
    NamerInitializers(new TestNamer)
  ).read(Yaml(yaml))

  test("returns the names of defined routers") {
    val linker = parse("""
routers:
- protocol: plain
  servers:
  - port: 1
- protocol: fancy
  servers:
  - port: 2
                       """)
    val handler = new RouterHandler(linker)
    val req = Request()
    val rsp = await(handler(req))
    assert(rsp.status == Status.Ok)
    assert(rsp.contentString ==
      s"""[{"label":"plain","protocol":"plain","servers":[{"ip":"127.0.0.1","port":1}]},""" +
      """{"label":"fancy","protocol":"fancy","servers":[{"ip":"127.0.0.1","port":2}]}]""")
  }
}
