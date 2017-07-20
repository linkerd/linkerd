package io.buoyant.linkerd

import com.twitter.conversions.time._
import com.twitter.finagle.Path
import com.twitter.finagle.filter.RequestSemaphoreFilter
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.transport.Transport
import com.twitter.util.{Return, Try}
import io.buoyant.config.Parser
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class ServerTest extends FunSuite {

  def parse(proto: ProtocolInitializer, yaml: String): Try[Server] = Try {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(proto)))
    val cfg = mapper.readValue[ServerConfig](yaml)
    cfg.mk(proto, "router")
  }

  val plainYaml = """
port: 1234
"""

  val fancyYaml = """
port: 1234
fancyServer: true
"""

  test("addr parsed") {
    val Return(server) = parse(TestProtocol.Plain, """
port: 4321
ip: 8.8.8.8
""")
    assert(server.addr == new InetSocketAddress("8.8.8.8", 4321))
  }

  test("addr no ip is loopback") {
    val Return(server) = parse(TestProtocol.Plain, """
port: 4320
""")
    assert(server.addr == new InetSocketAddress(InetAddress.getLoopbackAddress, 4320))
  }

  test("addr any ip") {
    val Return(server) = parse(TestProtocol.Plain, """
port: 432
ip: 0.0.0.0
""")
    assert(server.addr == new InetSocketAddress(432))
  }

  test("no port") {
    val yaml = """
ip: 127.1
"""
    assert(parse(TestProtocol.Plain, yaml).isThrow)
  }

  test("unknown server params error") {
    assert(parse(TestProtocol.Plain, fancyYaml).isThrow)
  }

  test("protocol-specific router params have no bearing on servers") {
    val yaml = """
port: 1234
fancyRouter: true
"""
    assert(parse(TestProtocol.Fancy, yaml).isThrow)
  }

  test("invalid tls configuration") {
    val yaml =
      """
        |port: 1234
        |tls:
        |  certPath: /foo/cert
      """.stripMargin
    assert(parse(TestProtocol.Plain, yaml).isThrow)
  }

  test("valid tls configuration") {
    val yaml =
      """
        |port: 1234
        |tls:
        |  certPath: /foo/cert
        |  keyPath: /foo/key
      """.stripMargin
    assert(parse(TestProtocol.Plain, yaml).get.params.apply[Transport.ServerSsl].e.isDefined)
  }

  test("tls configuration absent") {
    val yaml =
      """
        |port: 1234
      """.stripMargin
    assert(parse(TestProtocol.Plain, yaml).get.params.apply[Transport.ServerSsl].e.isEmpty)
  }

  test("maxConcurrentRequests") {
    val yaml =
      """
        |maxConcurrentRequests: 1000
      """.stripMargin
    assert(parse(TestProtocol.Plain, yaml).get.params[RequestSemaphoreFilter.Param].sem.get.numInitialPermits == 1000)
  }

  test("announce") {
    val yaml =
      """
        |announce:
        |- /#/io.l5d.foo/bar
      """.stripMargin
    assert(parse(TestProtocol.Plain, yaml).get.announce == Seq(Path.read("/#/io.l5d.foo/bar")))
  }
}
