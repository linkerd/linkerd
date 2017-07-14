package io.buoyant.linkerd

import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import io.buoyant.config.Parser
import io.buoyant.namer.{ConfiguredNamersInterpreter, InterpreterInitializer, TestInterpreter, TestInterpreterInitializer}
import io.buoyant.router.{Originator, RetryBudgetConfig, RoutingFactory}
import io.buoyant.test.Exceptions
import java.net.InetAddress
import org.scalatest.FunSuite

class RouterTest extends FunSuite with Exceptions {

  def parseConfig(
    yaml: String,
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy),
    interpreters: Seq[InterpreterInitializer] = Seq(TestInterpreterInitializer)
  ): RouterConfig =
    Parser.objectMapper(yaml, Iterable(protos, interpreters)).readValue[RouterConfig](yaml)

  def parse(
    yaml: String,
    params: Stack.Params = Stack.Params.empty,
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy),
    interpreters: Seq[InterpreterInitializer] = Seq(TestInterpreterInitializer),
    announcers: Seq[AnnouncerInitializer] = Seq(TestAnnouncerInitializer)
  ): Router = {
    val mapper = Parser.objectMapper(yaml, Iterable(protos, interpreters, announcers))
    val cfg = mapper.readValue[RouterConfig](yaml)
    val interpreter = cfg.interpreter.interpreter(cfg.routerParams)
    cfg.router(params + DstBindingFactory.Namer(interpreter))
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
    val DstBindingFactory.Namer(interpreter) = router.params[DstBindingFactory.Namer]
    assert(interpreter.isInstanceOf[ConfiguredNamersInterpreter])
  }

  test("with timeout") {
    val yaml =
      """|protocol: plain
         |client:
         |  requestAttemptTimeoutMs: 1234
         |servers:
         |- port: 4321
         |""".stripMargin
    val client = parseConfig(yaml).client.get.asInstanceOf[DefaultClient]
    assert(client.requestAttemptTimeoutMs == Some(1234))
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
    assertThrows[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("unknown protocol") {
    val yaml = """
protocol: boring
label: hummus
servers:
- port: 1234
"""
    assertThrows[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("router overrides global params") {
    val yaml = """
protocol: plain
label: yoghurt
dtab: /foo => /bah
servers:
- port: 1234
"""
    val defaultDtab = RoutingFactory.BaseDtab(() => Dtab.read("/foo => /bar"))
    val router = parse(yaml, Stack.Params.empty + defaultDtab)
    val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
    assert(dtab() == Dtab.read("/foo=>/bah"))
  }

  test("name interpreter specification") {
    val yaml =
      """protocol: plain
        |interpreter:
        |  kind: test
      """.stripMargin
    val router = parse(yaml, Stack.Params.empty)
    val DstBindingFactory.Namer(interpreter) = router.params[DstBindingFactory.Namer]
    assert(interpreter.isInstanceOf[TestInterpreter])
  }

  test("announcer") {
    val yaml = """
        |protocol: plain
        |announcers:
        |- kind: io.l5d.test
        |servers:
        |- announce:
        |    - /#/io.l5d.test/foobar
      """.
      stripMargin
    val router = parse(yaml, Stack.Params.empty)
    val (path, announcer) = router.initialize().announcers.head
    assert(path == Path.read("/#/io.l5d.test"))
    assert(announcer.isInstanceOf[TestAnnouncer])
  }

  test("with retries") {
    val yaml =
      """|protocol: plain
         |service:
         |  retries:
         |    backoff:
         |      kind: jittered
         |      minMs: 1
         |      maxMs: 1000
         |    budget:
         |      ttlSecs: 30
         |      minRetriesPerSec: 3
         |      percentCanRetry: 0.33
         |""".stripMargin
    val svc = parseConfig(yaml).service.get.asInstanceOf[DefaultSvc]
    assert(svc.retries == Some(RetriesConfig(
      Some(JitteredBackoffConfig(Some(1), Some(1000))),
      Some(RetryBudgetConfig(Some(30), Some(3), Some(0.33)))
    )))
  }

  test("with binding cache") {
    val yaml =
      """|protocol: plain
         |bindingCache:
         |  paths: 1000
         |  trees: 999
         |  bounds: 998
         |""".stripMargin
    val capacity = parseConfig(yaml).routerParams[DstBindingFactory.Capacity]
    assert(capacity.paths == 1000)
    assert(capacity.trees == 999)
    assert(capacity.bounds == 998)
    assert(capacity.clients == DstBindingFactory.Capacity.default.clients)
  }

  test("with originator") {
    val yaml =
      """|protocol: plain
         |originator: true
         |""".stripMargin
    val originator = parseConfig(yaml).routerParams[Originator.Param]
    assert(originator.originator)
  }
}
