package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import org.scalatest.FunSuite

class IngressIdentifierConfigTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = new IngressIdentifierConfig(None, None, None, None).newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[IngressIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
      |kind: io.l5d.ingress
      |useCapturingGroups: true
      |namespace: myNameSpace
      |host: 127.0.0.1
      |port: 6666
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IngressIdentifierInitializer)))
    val config = mapper.readValue[IngressIdentifierConfig](yaml)

    assert(config.namespace.isDefined)
    assert(config.namespace.get == "myNameSpace")

    assert(config.host.isDefined)
    assert(config.host.get == "127.0.0.1")

    assert(config.port.isDefined)
    assert(config.port.get.port == 6666)

    assert(config.useCapturingGroups.isDefined)
    assert(config.useCapturingGroups.get)
  }

}
