package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Status}
import io.buoyant.linkerd._
import io.buoyant.linkerd.protocol.ThriftInitializer
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ConfigHandlerTest extends FunSuite with Awaits {

  test("reserializes config") {
    val linker = Linker.parse("""
namers:
- kind: io.buoyant.linkerd.TestNamer
  buh: true
routers:
- protocol: plain
  servers:
  - ip: 127.0.0.1
    port: 1
- protocol: thrift
  client:
    thriftProtocol: binary
  servers:
  - port: 2
    thriftProtocol: compact
                             """, Seq(TestProtocol.Plain, TestProtocol.Fancy, TestNamerInitializer, ThriftInitializer))
    val handler = new ConfigHandler(linker, Seq(TestProtocol.Plain, TestProtocol.Fancy, TestNamerInitializer, ThriftInitializer))
    val req = Request()
    val rsp = await(handler(req))
    assert(rsp.status == Status.Ok)
    assert(rsp.contentString == """
      |{
      |  "namers":[
      |    {"kind":"io.buoyant.linkerd.TestNamer", "buh":true}
      |  ],
      |  "routers":[
      |    {"protocol":"plain","servers":[{"port":1, "ip":"localhost"}]},
      |    {
      |      "protocol":"thrift",
      |      "servers":[{"thriftProtocol":"compact", "port":2}],
      |      "client":{"thriftProtocol":"binary"}
      |    }
      |  ]
      |}""".stripMargin.replaceAll("\\s", ""))
  }
}
