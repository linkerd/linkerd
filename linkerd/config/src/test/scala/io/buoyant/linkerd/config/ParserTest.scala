package io.buoyant.linkerd.config

import org.scalatest.FunSuite
import io.buoyant.linkerd.config.http._
import io.buoyant.linkerd.config.thrift._

class ParserTest extends FunSuite {
  val YamlConfig = """
baseDtab: foo => bar ;

routers:
  - protocol: http
    httpUriInDst: true

  - protocol: http
    servers:
      - port: 8080
        ip: 0.0.0.0
    timeoutMs: 1000
    dstPrefix: /ext/http

  - protocol: thrift
    thriftFramed: false
    thriftMethodInDst: true
    baseDtab: |
      /thrift => /$/io.buoyant.fs/thrift;
"""

  val parsedYaml = Parser(YamlConfig)

  test("baseDtab on linker") {
    assert(parsedYaml.baseDtab == Some("foo => bar ;"))
  }

  test("simple http router") {
    val router = parsedYaml.routers(0).asInstanceOf[HttpRouterConfig]
    assert(router.httpUriInDst == true)
    assert(router.label == None)
  }

  test("thrift router") {
    val thriftRouter = parsedYaml.routers.last.asInstanceOf[ThriftRouterConfig]
    assert(thriftRouter.thriftFramed == Some(false))
    assert(thriftRouter.thriftMethodInDst == Some(true))
  }
}
