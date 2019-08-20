package io.buoyant.namerd.iface

import com.twitter.finagle.buoyant.SocketOptionsConfig
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite

class DestinationIfaceInitializerTest extends FunSuite{

  test("initialization"){
    val yaml = s"""
       |kind: io.l5d.destination
     """.stripMargin

    val config = Parser.objectMapper(yaml, Iterable(Seq(new DestinationIfaceInitializer)))
      .readValue[DestinationIfaceConfig](yaml)
    assert(config.addr.getHostString == "localhost")
  }

  test("read socket options"){
    val expectedOpts = SocketOptionsConfig(reusePort = Some(true))
    val yaml = s"""
       |kind: io.l5d.destination
       |ip: 0.0.0.0
       |port: 8085
       |socketOptions:
       |  noDelay: true
       |  reuseAddr: true
       |  reusePort: true
     """.stripMargin

    val config = Parser.objectMapper(yaml, Iterable(Seq(new DestinationIfaceInitializer)))
      .readValue[DestinationIfaceConfig](yaml)
    config.socketOptions match {
      case None => fail(s"socket options is None. Expected $expectedOpts")
      case Some(opts) => assert(opts == expectedOpts)
    }
  }
}
