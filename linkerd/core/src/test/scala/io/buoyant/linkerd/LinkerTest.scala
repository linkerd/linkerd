package io.buoyant.linkerd

import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.DefaultInterpreter
import com.twitter.finagle.param
import com.twitter.finagle.tracing.DefaultTracer
import io.buoyant.linkerd.config.{ConflictingLabels, ConflictingPorts, ConflictingSubtypes}
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class LinkerTest extends FunSuite {

  def parse(
    yaml: String,
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy),
    namers: Seq[NamerInitializer] = Seq(TestNamerInitializer),
    tracers: Seq[TracerInitializer] = Seq(TestTracerInitializer)
  ) = {
    Linker.load(yaml, protos ++ namers ++ tracers)
  }

  test("basic") {
    val linker = parse("""
routers:
- protocol: plain
  servers:
  - port: 1
- protocol: fancy
  servers:
  - port: 2
""")
    val routers = linker.routers

    val DstBindingFactory.Namer(namer) = linker.routers.head.params[DstBindingFactory.Namer]
    assert(namer == DefaultInterpreter)
    assert(linker.interpreter == DefaultInterpreter)

    assert(routers.size == 2)

    assert(routers(0).label == "plain")
    assert(routers(0).protocol == TestProtocol.Plain)
    assert(routers(0).servers.size == 1)
    assert(routers(0).servers(0).addr.getAddress == InetAddress.getLoopbackAddress)
    assert(routers(0).servers(0).addr.getPort == 1)

    assert(routers(1).label == "fancy")
    assert(routers(1).protocol == TestProtocol.Fancy)
    assert(routers(1).params[TestProtocol.FancyParam].pants == false)
    assert(routers(1).servers.size == 1)
    assert(routers(1).servers(0).addr.getAddress == InetAddress.getLoopbackAddress)
    assert(routers(1).servers(0).addr.getPort == 2)
  }

  test("empty object") {
    val e = intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse("") }
  }

  test("list instead of an object") {
    val yaml = """
- foo
- bar
"""
    val ut = intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("invalid routers") {
    val yaml = """
routers:
  protocol: foo
"""
    val ut = intercept[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("protocol-specific params not supported in global context") {
    val yaml = """
fancyRouter: true
routers:
- protocol: plain
  servers:
  - port: 1
- protocol: fancy
  servers:
  - port: 2
"""
    intercept[com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException] { parse(yaml) }
  }

  test("router labels conflict") {
    val yaml = """
routers:
- protocol: plain
  servers:
  - port: 1
- protocol: plain
  servers:
  - port: 2
"""
    intercept[ConflictingLabels] { parse(yaml) }
  }

  test("router labels don't conflict") {
    val yaml = """
routers:
- protocol: plain
  servers:
  - port: 1
- protocol: plain
  label: yohourt
  servers:
  - port: 2
"""
    assert(parse(yaml).routers.map(_.label) == Seq("plain", "yohourt"))
  }

  test("servers conflict across routers") {
    val yaml = """
routers:
- protocol: plain
  label: router1
  servers:
  - port: 2
- protocol: plain
  label: router2
  servers:
  - port: 1
  - port: 2
  - port: 3
"""
    intercept[ConflictingPorts] { parse(yaml) }
  }

  test("servers conflict within a router") {
    val yaml = """
routers:
- protocol: plain
  servers:
  - port: 1234
  - port: 1234
"""
    intercept[ConflictingPorts] { parse(yaml) }
  }

  test("servers don't conflict on different ips") {
    val yaml = """
routers:
- protocol: plain
  servers:
  - ip: 127.0.0.2
    port: 3
- protocol: fancy
  servers:
  - port: 3
"""
    assert(parse(yaml).routers.flatMap(_.servers.map(_.addr)) == Seq(
      new InetSocketAddress("127.2", 3),
      new InetSocketAddress(InetAddress.getLoopbackAddress, 3)
    ))
  }

  test("with namers") {
    val yaml = """
namers:
- kind: io.buoyant.linkerd.TestNamer
  prefix: /n
  buh: true
routers:
- protocol: plain
  servers:
  - port: 1
"""
    val linker = parse(yaml)
    val DstBindingFactory.Namer(namer) = linker.routers.head.params[DstBindingFactory.Namer]
    assert(namer != DefaultInterpreter)
    assert(linker.interpreter != DefaultInterpreter)
  }

  test("with tracers") {
    val yaml = """
tracers:
- kind: io.buoyant.linkerd.TestTracer
routers:
- protocol: plain
  servers:
  - port: 1
"""
    val linker = parse(yaml)
    val param.Tracer(tracer) = linker.routers.head.params[param.Tracer]
    assert(tracer != DefaultTracer)
    assert(linker.tracer != DefaultTracer)
  }

  test("with admin") {
    val yaml = """
admin:
  port: 9991
routers:
- protocol: plain
  servers:
  - port: 1
"""
    val linker = parse(yaml)
    assert(linker.admin.port.port == 9991)
  }

  test("conflicting subtypes") {
    val yaml = """
namers:
- kind: io.buoyant.linkerd.TestNamer
routers:
- protocol: plain
  servers:
  - port: 1
    """
    intercept[ConflictingSubtypes] { parse(yaml, namers = Seq(TestNamerInitializer, ConflictingNamerInitializer)) }
  }
}
