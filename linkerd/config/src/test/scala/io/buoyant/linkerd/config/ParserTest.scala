package io.buoyant.linkerd.config

import java.net.{InetAddress, InetSocketAddress}

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
    label: httpExt
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

  val (baseConfig, configWithDefaults) = Parser(YamlConfig).right.get

  test("baseDtab on linker") {
    assert(baseConfig.baseDtab == Some("foo => bar ;"))
  }

  test("simple http router") {
    val baseRouter = baseConfig.routers.get.head.asInstanceOf[HttpRouterConfig]
    assert(baseRouter.httpUriInDst == Some(true))
    assert(baseRouter.label == None) // label should default to protocol
    val defaultedRouter = configWithDefaults.routers.get.head.asInstanceOf[HttpRouterConfig]
    assert(defaultedRouter.label == Some("http")) // label should default to protocol
  }

  test("thrift router") {
    val baseRouter = baseConfig.routers.get.last.asInstanceOf[ThriftRouterConfig]
    assert(baseRouter.thriftFramed == Some(false))
    assert(baseRouter.thriftMethodInDst == Some(true))
    assert(baseRouter.servers.isEmpty)

    val defaultedRouter = configWithDefaults.routers.get.last.asInstanceOf[ThriftRouterConfig]
    assert(defaultedRouter.thriftFramed == Some(false))
    assert(defaultedRouter.servers.get.head.port == None)
  }

  // validation tests begin here

  test("no routers configured") {
    val noRouters = "baseDtab: foo => bar;"
    val invalid = Parser(noRouters)
    assert(invalid.isLeft)
    val errors = invalid.left.get
    assert(errors.size == 1)
    assert(errors.head == NoRoutersSpecified)
  }

  test("conflicts between routers and servers") {
    val conflictingRouters =
      """
        |routers:
        |  - protocol: http
        |    label: conflicts!
        |    servers:
        |      - port: 1234
        |
        |  - protocol: http
        |    label: conflicts!
        |    servers:
        |      - port: 1234
      """.stripMargin
    val invalid = Parser(conflictingRouters)
    assert(invalid.isLeft)
    val errors = invalid.left.get
    assert(errors.size == 2)
    assert(errors contains ConflictingLabels("conflicts!"))
    val conflictAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
    assert(errors contains ConflictingPorts(conflictAddr, conflictAddr))
  }
}
