package io.buoyant.linkerd.config

import com.fasterxml.jackson.core.JsonParseException
import com.twitter.finagle.Dtab
import com.twitter.util.{Return, Throw, Try}
import io.buoyant.linkerd.config.http._
import io.buoyant.linkerd.config.namers.FileSystemNamerConfig
import io.buoyant.linkerd.config.thrift._
import org.scalatest.FunSuite


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
  def validatedConfig = configs.validatedConfig match {
    case Return(cfg) => cfg
    case Throw(ex) => fail(s"expected config to parse successfully, but got $ex")
  }

  test("baseDtab on linker") {
    assert(baseConfig.baseDtab === Some(Dtab.read("/foo => /bar ;")))
  }

  test("simple http router") {
    val baseRouter = baseConfig.routers.get.head.asInstanceOf[HttpRouterConfig]
    assert(baseRouter.httpUriInDst === Some(true))
    assert(baseRouter.label == None) // label should default to protocol

    /*
    TODO: assertions on Stack.Params!!

    val validatedRouter = validatedConfig.routers.head
    assert(validatedRouter.label == "http") // label should default to protocol
    val protocol = validatedRouter.protocol.asInstanceOf[HttpRouterConfig.Protocol]

    val server = validatedRouter.servers.head
    assert(server.addr.getHostName == "localhost") // non-specified servers default to listening on localhost
    assert(server.addr.getPort == 0) // non-specified servers should default to zero
    */
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

    /* TODO: figure out how to test against Stack.Params!!!
    val defaultedRouter = validatedConfig.routers.last
    val protocol = defaultedRouter.protocol.asInstanceOf[ThriftRouterConfig.Protocol]
    assert(protocol.thriftFramed == false)
    assert(defaultedRouter.servers.head.addr.getPort == 0)
    */
  }

  test("filesystem namer") {
    val baseNamer = baseConfig.namers.get.head.asInstanceOf[FileSystemNamerConfig]
    assert(baseNamer.rootDir.path === java.nio.file.Paths.get("/tmp"))

    val validatedNamer = validatedConfig.namers.head
    /* TODO: figure out how to assert against Stack.Params!!11!!
    assert(validatedNamer.prefix == Path.read("/io.l5d.fs"))
    val proto = validatedNamer.protocol.asInstanceOf[FileSystemNamerConfig.ValidatedProtocol]
    assert(proto.rootDir == java.nio.file.Paths.get("/tmp"))
    */
  }

  // validation tests begin here

  def extractError[_](cfg: Try[_]): Throwable = cfg match {
    case Return(_) => fail("error expected")
    case Throw(e) => e
  }

  test("invalid json") {
    val invalidJson = "{ whoa }"
    val parsed = Parser(invalidJson)
    assert(parsed.parsedConfig.isThrow) // JSON parsing failures will yield no parsed configuration.
    val invalid = parsed.validatedConfig
    assert(invalid.isThrow)

    val error = extractError(invalid)
    assert(error.isInstanceOf[JsonParseException])
    assert(error.getMessage.startsWith("Unexpected character"))

  }

  test("no routers configured") {
    val noRouters = "baseDtab: /foo => /bar;"
    val invalid = Parser(noRouters).validatedConfig
    assert(invalid.isThrow)

    val error = extractError(invalid)
    assert(error === NoRoutersSpecified)

  }

  test("label conflict") {
    val conflictingRouters =
      """
        |routers:
        |  - protocol: http
        |    label: conflicts!
        |
        |  - protocol: http
        |    label: conflicts!
      """.stripMargin
    val invalid = Parser(conflictingRouters).validatedConfig
    assert(invalid.isThrow)
    val error = extractError(invalid)
    assert(error === ConflictingLabels("conflicts!"))
  }
}
