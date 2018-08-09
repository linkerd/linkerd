package io.buoyant.namerd.iface

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
}
