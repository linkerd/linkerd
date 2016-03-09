package io.buoyant.linkerd

import com.twitter.finagle.{Dtab, Stack}
import io.buoyant.linkerd.config.Parser
import io.buoyant.router.RoutingFactory
import java.net.InetAddress
import org.scalatest.FunSuite

class RouterTest extends FunSuite {

  def parse(
    yaml: String,
    params: Stack.Params = Stack.Params.empty,
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy)
  ): Router = {
    val mapper = Parser.objectMapper(yaml, protos)
    val cfg = mapper.readValue[RouterConfig](yaml)
    cfg.router(params)
  }

  test("with label") {
    val yaml = """
protocol: plain
label: yoghurt
servers:
- port: 1234
"""
    val router = parse(yaml)
    assert(router.protocol == TestProtocol.Plain)
    assert(router.label == "yoghurt")
    assert(router.servers.size == 1)
    assert(router.servers.head.router == "yoghurt")
    assert(router.servers.head.addr.getAddress == InetAddress.getLoopbackAddress)
    assert(router.servers.head.addr.getPort == 1234)
  }

  test("loopback & protocol-specific default port used when no ports specified") {
    val yaml = """
protocol: plain
label: yoghurt
servers:
  - {}
"""
    val router = parse(yaml)
    assert(router.servers.head.ip.isLoopbackAddress)
    assert(router.servers.head.port == 13)
  }

  test("no protocol") {
    val yaml = """
label: yoghurt
servers:
- port: 1234
"""
    intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("unknown protocol") {
    val yaml = """
protocol: boring
label: hummus
servers:
- port: 1234
"""
    intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("router overrides global params") {
    val yaml = """
protocol: plain
label: yoghurt
baseDtab: /foo => /bah
servers:
- port: 1234
"""
    val defaultDtab = RoutingFactory.BaseDtab(() => Dtab.read("/foo => /bar"))
    val router = parse(yaml, Stack.Params.empty + defaultDtab)
    val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
    assert(dtab() == Dtab.read("/foo=>/bah"))
  }
}
