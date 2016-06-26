package io.buoyant.linkerd

import com.twitter.conversions.time._
import com.twitter.finagle.{Dtab, Service, ServiceFactory, Stack, Stackable}
import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.util.Duration
import io.buoyant.config.Parser
import io.buoyant.namer.{ConfiguredNamersInterpreter, InterpreterInitializer, TestInterpreterInitializer, TestInterpreter}
import io.buoyant.router.RoutingFactory
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
    interpreters: Seq[InterpreterInitializer] = Seq(TestInterpreterInitializer)
  ): Router = {
    val cfg = parseConfig(yaml, protos, interpreters)
    val interpreter = cfg.interpreter.newInterpreter(cfg.routerParams)
    cfg.router(params + DstBindingFactory.Namer(interpreter))
  }

  test("with label") {
    val yaml =
      """|protocol: plain
         |label: yoghurt
         |servers:
         |- port: 1234
         |""".stripMargin
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

  test("with timeouts") {
    val yaml =
      """|protocol: plain
         |timeoutMs: 1234
         |client:
         |  timeoutMs: 2345
         |servers:
         |- port: 8888
         |  timeoutMs: 3456
         |""".stripMargin
    val router = parse(yaml)

    assert(router.servers.size == 1)
    val server = router.servers.head

    assert(router.params[Router.RouterTimeout] == Router.RouterTimeout(1234.millis))
    assert(router.params[Router.ClientTimeout] == Router.ClientTimeout(2345.millis))
    assert(server.params[Router.ServerTimeout] == Router.ServerTimeout(3456.millis))

    assert(Router.serverParams(router, server)[Router.RouterTimeout] ==
      Router.RouterTimeout(1234.millis))
  }

  private val timeoutStack = {
    val leaf: Stackable[Duration] =
      new Stack.Module1[TimeoutFilter.Param, Duration] {
        val role = Stack.Role("leaf")
        val description = "checks timeouts"
        def make(_timeout: TimeoutFilter.Param, _next: Duration): Duration = _timeout.timeout
      }
    leaf +: Stack.Leaf(Stack.Role("ep"), Duration.Top)
  }

  private val clientStack = Router.ClientTimeout.module[Duration] +: timeoutStack
  private val serverStack = Router.ServerTimeout.module[Duration] +: timeoutStack

  test("ClientTimeout.module") {
    val params = Stack.Params.empty +
      Router.ClientTimeout(1234.millis)
    assert(clientStack.make(params) == 1234.millis)
  }

  test("ClientTimeout.module: lesser of router and client timeouts") {
    val params = Stack.Params.empty +
      Router.ClientTimeout(234.millis) +
      Router.RouterTimeout(123.millis)
    assert(clientStack.make(params) == 123.millis)
  }

  test("ServerTimeout.module") {
    val params = Stack.Params.empty +
      Router.ServerTimeout(1234.millis)
    assert(serverStack.make(params) == 1234.millis)
  }

  test("ServerTimeout.module: use router timeout if server timeout is not specified") {
    val params = Stack.Params.empty +
      Router.RouterTimeout(4321.millis)
    assert(serverStack.make(params) == 4321.millis)
  }

  test("ServerTimeout.module: do not use router timeout if server timeout is specified") {
    val params = Stack.Params.empty +
      Router.RouterTimeout(4321.millis) +
      Router.ServerTimeout(1234.millis)
    assert(serverStack.make(params) == 1234.millis)
  }

  test("loopback & protocol-specific default port used when no ports specified") {
    val yaml =
      """|protocol: plain
         |label: yoghurt
         |servers:
         |- {}
         |""".stripMargin
    val router = parse(yaml)
    assert(router.servers.head.ip.isLoopbackAddress)
    assert(router.servers.head.port == 13)
  }

  test("no protocol") {
    val yaml =
      """|label: yoghurt
         |servers:
         |- port: 1234
         |""".stripMargin
    assertThrows[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("unknown protocol") {
    val yaml =
      """|protocol: boring
         |label: hummus
         |servers:
         |- port: 1234
         |""".stripMargin
    assertThrows[com.fasterxml.jackson.databind.JsonMappingException] { parse(yaml) }
  }

  test("router overrides global params") {
    val yaml =
      """|protocol: plain
         |label: yoghurt
         |baseDtab: /foo => /bah
         |servers:
         |- port: 1234
         |""".stripMargin
    val defaultDtab = RoutingFactory.BaseDtab(() => Dtab.read("/foo => /bar"))
    val router = parse(yaml, Stack.Params.empty + defaultDtab)
    val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
    assert(dtab() == Dtab.read("/foo=>/bah"))
  }

  test("name interpreter specification") {
    val yaml =
      """|protocol: plain
         |interpreter:
         |  kind: test
         |""".stripMargin
    val router = parse(yaml, Stack.Params.empty)
    val DstBindingFactory.Namer(interpreter) = router.params[DstBindingFactory.Namer]
    assert(interpreter.isInstanceOf[TestInterpreter])
  }

  test("with retries") {
    val yaml =
      """|protocol: plain
         |client:
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
    assert(parseConfig(yaml).client.flatMap(_.retries) == Some(RetriesConfig(
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
}
