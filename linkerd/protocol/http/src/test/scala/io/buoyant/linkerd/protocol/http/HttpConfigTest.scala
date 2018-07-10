package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Method, Request}
import com.twitter.finagle.{Dtab, Path, Stack}
import io.buoyant.config.Parser
import io.buoyant.linkerd.RouterConfig
import io.buoyant.linkerd.protocol.{HttpConfig, HttpInitializer}
import io.buoyant.router.Http
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.router.http.TimestampHeaderFilter
import io.buoyant.test.Awaits
import io.buoyant.test.FunSuite

class HttpConfigTest extends FunSuite with Awaits {

  def parse(yaml: String): HttpConfig = {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HttpInitializer), Seq(MethodAndHostIdentifierInitializer, PathIdentifierInitializer)))
    mapper.readValue[RouterConfig](yaml).asInstanceOf[HttpConfig]
  }

  test("parse config") {
    val yaml = s"""
                  |protocol: http
                  |httpAccessLog: access.log
                  |httpAccessLogRollPolicy: daily
                  |httpAccessLogAppend: true
                  |httpAccessLogRotateCount: -1
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |maxChunkKB: 8
                  |maxHeadersKB: 8
                  |maxInitialLineKB: 4
                  |maxRequestKB: 5120
                  |maxResponseKB: 5120
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)
    assert(config.httpAccessLog.get == "access.log")
    assert(config.httpAccessLogRollPolicy.get == "daily")
    assert(config.httpAccessLogAppend.get)
    assert(config.httpAccessLogRotateCount.get == -1)
    assert(config.maxChunkKB.get == 8)
    assert(config.maxHeadersKB.get == 8)
    assert(config.maxInitialLineKB.get == 4)
    assert(config.maxRequestKB.get == 5120)
    assert(config.maxResponseKB.get == 5120)
  }

  test("default identifier") {
    val yaml = s"""
                  |protocol: http
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)
    val identifier = config.routerParams(Stack.Params.empty)[Http.param.HttpIdentifier]
      .id(Path.read("/svc"), () => Dtab.empty)
    val req = Request(Method.Get, "/one/two/three")
    req.host = "host.com"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst.path ==
        Path.read("/svc/host.com")
    )
  }

  test("single identifier") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)
    val identifier = config.routerParams(Stack.Params.empty)[Http.param.HttpIdentifier]
      .id(Path.read("/svc"), () => Dtab.empty)
    val req = Request(Method.Get, "/one/two/three")
    req.host = "host.com"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst.path ==
        Path.read("/svc/1.1/GET/host.com")
    )
  }

  test("identifier list") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |- kind: io.l5d.methodAndHost
                  |- kind: io.l5d.path
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)
    val identifier = config.routerParams(Stack.Params.empty)[Http.param.HttpIdentifier]
      .id(Path.read("/svc"), () => Dtab.empty)
    val req = Request(Method.Get, "/one/two/three")
    req.host = "host.com"
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst.path ==
        Path.read("/svc/1.1/GET/host.com")
    )
  }

  test("identifier list with fallback") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |- kind: io.l5d.methodAndHost
                  |- kind: io.l5d.path
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)
    val identifier = config.routerParams(Stack.Params.empty)[Http.param.HttpIdentifier]
      .id(Path.read("/svc"), () => Dtab.empty)
    val req = Request(Method.Get, "/one/two/three")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst.path ==
        Path.read("/svc/one")
    )
  }

  test("timestamp header") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |servers:
                  |- port: 5000
                  |  timestampHeader: x-request-start
      """.stripMargin
    val config = parse(yaml)
    val timestamper = config.servers
      .head
      .serverParams[TimestampHeaderFilter.Param]
    assert(timestamper.header.contains("x-request-start"))

  }
}
