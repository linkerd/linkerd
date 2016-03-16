package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Status}
import io.buoyant.linkerd._
import io.buoyant.namer.TestNamerInitializer
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class RouterHandlerTest extends FunSuite with Awaits {
  val protos = Seq(TestProtocol.Plain, TestProtocol.Fancy)
  val namers = Seq(TestNamerInitializer)

  test("returns the names of defined routers") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |- protocol: fancy
         |  servers:
         |  - port: 2
         |""".stripMargin
    val linker = Linker.Initializers(protos, namers).load(yaml)
    val handler = new RouterHandler(linker)
    val req = Request()
    val rsp = await(handler(req))
    assert(rsp.status == Status.Ok)
    assert(rsp.contentString ==
      """[{"label":"plain","protocol":"plain","servers":[{"ip":"127.0.0.1","port":1}]},""" +
      """{"label":"fancy","protocol":"fancy","servers":[{"ip":"127.0.0.1","port":2}]}]""")
  }
}
