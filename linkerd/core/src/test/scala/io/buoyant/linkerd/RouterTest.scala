package io.buoyant.linkerd

import com.twitter.finagle.{Dtab, Stack, param}
import io.buoyant.router.RoutingFactory
import java.net.InetAddress
import org.scalatest.FunSuite

class RouterTest extends FunSuite {

  def parse(
    yaml: String,
    params: Stack.Params = Stack.Params.empty,
    protos: ProtocolInitializers = TestProtocol.DefaultInitializers
  ) = Router.read(Yaml(yaml), params, protos)

  test("with label") {
    val yaml = """
protocol: plain
label: yoghurt
servers:
- port: 1234
"""
    val router = parse(yaml)
    assert(router.protocol == TestProtocol.Plain)
    assert(router.name == "yoghurt")
    assert(router.servers.size == 1)
    assert(router.servers.head.name == "yoghurt/127.0.0.1/1234")
    assert(router.servers.head.addr.getAddress == InetAddress.getLoopbackAddress)
    assert(router.servers.head.addr.getPort == 1234)
  }

  test("loopback & protocol-specific default port used when no ports specified") {
    val yaml = """
protocol: plain
label: yoghurt
"""
    val router = parse(yaml)
    assert(router.servers.head.params[Server.Ip].ip.isLoopbackAddress)
    assert(router.servers.head.port == 13)
  }

  test("no protocol") {
    val yaml = """
label: yoghurt
servers:
- port: 1234
"""
    intercept[Parsing.Error] { parse(yaml) }
  }

  test("unknown protocol") {
    val yaml = """
protocol: boring
label: hummus
servers:
- port: 1234
"""
    intercept[Parsing.Error] { parse(yaml) }
  }

  test("protocol-specific params") {
    val yaml = """
protocol: fancy
fancyRouter: true
servers:
- port: 1234
  fancyServer: true
"""
    val router = parse(yaml)
    assert(router.protocol == TestProtocol.Fancy)

    assert(router.params[param.Label].label == TestProtocol.Fancy.name)
    assert(router.params[TestProtocol.Fancy.Pants].fancy)

    assert(router.servers.size == 1)
    assert(router.servers.head.addr.getAddress == InetAddress.getLoopbackAddress)
    assert(router.servers.head.addr.getPort == 1234)

    assert(router.servers.head.params[param.Label].label == "fancy/127.0.0.1/1234")
    assert(router.servers.head.params[TestProtocol.Fancy.Pants].fancy)

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

  test("servers must be differentiated") {
    val yaml = """
protocol: plain
servers:
- port: 1234
- port: 1234
"""
    intercept[Parsing.Error] { parse(yaml) }
  }
}
