package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Method, Request}
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.config.Parser
import io.buoyant.linkerd.RouterConfig
import io.buoyant.linkerd.protocol.{HttpConfig, HttpInitializer}
import io.buoyant.router.Http
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HttpConfigTest extends FunSuite with Awaits {

  def parse(yaml: String): HttpConfig = {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HttpInitializer), Seq(MethodAndHostIdentifierInitializer, PathIdentifierInitializer)))
    mapper.readValue[RouterConfig](yaml).asInstanceOf[HttpConfig]
  }

  test("parse config") {
    val yaml = s"""
                  |protocol: http
                  |httpAccessLog: access.log
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
    val identifier = config.routerParams[Http.param.HttpIdentifier]
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
    val identifier = config.routerParams[Http.param.HttpIdentifier]
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
    val identifier = config.routerParams[Http.param.HttpIdentifier]
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
    val identifier = config.routerParams[Http.param.HttpIdentifier]
      .id(Path.read("/svc"), () => Dtab.empty)
    val req = Request(Method.Get, "/one/two/three")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst.path ==
        Path.read("/svc/one")
    )
  }
}
