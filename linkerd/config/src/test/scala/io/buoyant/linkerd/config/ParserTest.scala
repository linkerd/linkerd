package io.buoyant.linkerd.config

import java.net.{InetAddress, InetSocketAddress}

import cats.data.ValidatedNel
import cats.std.list._
import com.twitter.finagle.Path
import io.buoyant.linkerd.config.namers.FileSystemNamerConfig
import org.scalatest.FunSuite
import io.buoyant.linkerd.config.http._
import io.buoyant.linkerd.config.thrift._


class ParserTest extends FunSuite {
  val YamlConfig = """
baseDtab: /foo => /bar ;

namers:
  - kind: io.l5d.fs
    rootDir: /tmp

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
  val configs: ParseResult = Parser(YamlConfig)
  def baseConfig = configs.parsedConfig.get
  def validatedConfig = configs.validatedConfig.fold(
    { errs => fail(s"expected config to parse successfully, but got ${errs.unwrap}")},
    identity)

  test("baseDtab on linker") {
    assert(baseConfig.baseDtab == Some("/foo => /bar ;"))
  }

  test("simple http router") {
    val baseRouter = baseConfig.routers.get.head.asInstanceOf[HttpRouterConfig]
    assert(baseRouter.httpUriInDst == Some(true))
    assert(baseRouter.label == None) // label should default to protocol

    val validatedRouter = validatedConfig.routers.head
    assert(validatedRouter.label == "http") // label should default to protocol
    val protocol = validatedRouter.protocol.asInstanceOf[HttpRouterConfig.Protocol]

    val server = validatedRouter.servers.head
    assert(server.addr.getHostName == "localhost") // non-specified servers default to listening on localhost
    assert(server.addr.getPort == 0) // non-specified servers should default to zero
  }

  test("http router with more configuration") {
    val baseRouter = baseConfig.routers.get(1).asInstanceOf[HttpRouterConfig]
    assert(baseRouter.httpUriInDst == None)
  }

  test("thrift router") {
    val baseRouter = baseConfig.routers.get.last.asInstanceOf[ThriftRouterConfig]
    assert(baseRouter.thriftFramed == Some(false))
    assert(baseRouter.thriftMethodInDst == Some(true))
    assert(baseRouter.servers.isEmpty)

    val defaultedRouter = validatedConfig.routers.last
    val protocol = defaultedRouter.protocol.asInstanceOf[ThriftRouterConfig.Protocol]
    assert(protocol.thriftFramed == false)
    assert(defaultedRouter.servers.head.addr.getPort == 0)
  }

  test("filesystem namer") {
    val baseNamer = baseConfig.namers.get.head.asInstanceOf[FileSystemNamerConfig]
    assert(baseNamer.rootDir == Some("/tmp"))

    val validatedNamer = validatedConfig.namers.head
    assert(validatedNamer.prefix == Path.read("/io.l5d.fs"))
    val proto = validatedNamer.protocol.asInstanceOf[FileSystemNamerConfig.ValidatedProtocol]
    assert(proto.rootDir == java.nio.file.Paths.get("/tmp"))
  }

  // validation tests begin here

  def extractErrors(cfg: ValidatedNel[ConfigError, LinkerConfig.Validated]): List[ConfigError] =
    cfg.fold(
      identity,
      { _ => fail("error expected") }
    ).unwrap

  test("invalid json") {
    val invalidJson = "{ whoa }"
    val parsed = Parser(invalidJson)
    assert(parsed.parsedConfig.isEmpty) // JSON parsing failures will yield no parsed configuration.
    val invalid = parsed.validatedConfig
    assert(invalid.isInvalid)

    val errors = extractErrors(invalid)
    assert(errors.size == 1)
    errors.head match {
      case InvalidSyntax(msg) => assert(msg contains "Unexpected character")
      case _ => fail("unexpected parser error")
    }
  }

  test("no routers configured") {
    val noRouters = "baseDtab: foo => bar;"
    val invalid = Parser(noRouters).validatedConfig
    assert(invalid.isInvalid)

    val errors = extractErrors(invalid)
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
    val invalid = Parser(conflictingRouters).validatedConfig
    assert(invalid.isInvalid)
    val errors = extractErrors(invalid)

    assert(errors.size == 2)
    assert(errors contains ConflictingLabels("conflicts!"))
    val conflictAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
    assert(errors contains ConflictingPorts(conflictAddr, conflictAddr))
  }
}
