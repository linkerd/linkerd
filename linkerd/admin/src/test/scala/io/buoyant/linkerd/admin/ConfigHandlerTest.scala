package io.buoyant.linkerd.admin

import com.twitter.finagle.http.{Request, Status}
import io.buoyant.admin.ConfigHandler
import io.buoyant.linkerd._
import io.buoyant.linkerd.protocol.ThriftInitializer
import io.buoyant.namer.{DefaultInterpreterInitializer, TestNamerInitializer}
import io.buoyant.test.FunSuite
import io.buoyant.transformer.perHost.SpecificHostTransformerInitializer

class ConfigHandlerTest extends FunSuite {

  test("reserializes config") {
    val initializers = Linker.Initializers(
      protocol = Seq(TestProtocol.Plain, TestProtocol.Fancy, ThriftInitializer),
      namer = Seq(TestNamerInitializer)
    )
    val linker = Linker.parse("""
namers:
- kind: test
  buh: true
routers:
- protocol: plain
  servers:
  - ip: 127.0.0.1
    port: 1
- protocol: thrift
  thriftProtocol: binary
  client:
    thriftFramed: true
  servers:
  - port: 2
    thriftFramed: false
                             """, initializers)
    val handler = new ConfigHandler(linker, initializers.iter)
    val req = Request()
    val rsp = await(handler(req))
    assert(rsp.status == Status.Ok)
    assertJsonEquals(rsp.contentString, """
      |{
      |  "namers":[
      |    {"buh":true, "kind": "test"}
      |  ],
      |  "routers":[
      |    {"protocol":"plain","servers":[{"port":1, "ip":"localhost"}]},
      |    {
      |      "protocol":"thrift",
      |      "thriftProtocol":"binary",
      |      "servers":[{"thriftFramed":false, "port":2}],
      |      "client":{"thriftFramed":true, "kind":"io.l5d.global"}
      |    }
      |  ]
      |}""".stripMargin)
  }

  test("specificHost config") {
    val initializers = Linker.Initializers(
      protocol = Seq(ThriftInitializer),
      interpreter = Seq(DefaultInterpreterInitializer),
      transformer = Seq(new SpecificHostTransformerInitializer)
    )
    val linker = Linker.parse("""
routers:
- protocol: thrift
  interpreter:
    kind: default
    transformers:
    - kind: io.l5d.specificHost
      host: 192.168.99.100
                              """, initializers)
    val handler = new ConfigHandler(linker, initializers.iter)
    val req = Request()
    val rsp = await(handler(req))
    assert(rsp.status == Status.Ok)
    assertJsonEquals(rsp.contentString, """
      |{
      |  "routers":[
      |    {
      |      "protocol":"thrift",
      |      "servers":[],
      |      "interpreter":{
      |        "kind":"default",
      |        "transformers":[{"kind":"io.l5d.specificHost", "host":"192.168.99.100"}]
      |      }
      |    }
      |  ]
      |}""".stripMargin)
  }
}
