package io.buoyant.linkerd.protocol.http

import com.twitter.conversions.StorageUnitOps._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.param.{FixedLengthStreamedAfter, Streaming}
import com.twitter.finagle.http.{Method, Request}
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.util.Future
import io.buoyant.config.Parser
import io.buoyant.linkerd.{IdentifierInitializer, RouterConfig}
import io.buoyant.linkerd.protocol.{HttpConfig, HttpIdentifierConfig, HttpInitializer}
import io.buoyant.router.Http
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier}
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
                  |maxHeadersKB: 8
                  |maxInitialLineKB: 4
                  |streamingEnabled: true
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)
    assert(config.httpAccessLog.get == "access.log")
    assert(config.httpAccessLogRollPolicy.get == "daily")
    assert(config.httpAccessLogAppend.get)
    assert(config.httpAccessLogRotateCount.get == -1)
    assert(config.maxHeadersKB.get == 8)
    assert(config.maxInitialLineKB.get == 4)
    assert(config.streamingEnabled.contains(true))

  }

  test("DefaultsTest - no streaming, no streamAfterContentLengthKB") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |maxInitialLineKB: 4
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)

    val streaming = config.routerParams(Stack.Params.empty)[Streaming]
    assert(streaming.enabled)
    /*  wish we could assert this, cause the "default" was 5meg
    streaming match {
      case Streaming.Enabled(fixedLengthStreamedAfter) => assert(fixedLengthStreamedAfter == 5.kilobytes)
      case _ => fail()
    }
    */
  }

  test("DefaultsTest - no streaming, streamAfterContentLengthKB present") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |maxInitialLineKB: 4
                  |streamAfterContentLengthKB: 5
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)

    val streaming = config.routerParams(Stack.Params.empty)[Streaming]
    assert(streaming.enabled)
  }

  test("DefaultsTest - Stream enabled, no streamAfter") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |maxInitialLineKB: 4
                  |streamAfterContentLengthKB: 5
                  |streamingEnabled: true
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)

    val streaming = config.routerParams(Stack.Params.empty)[Streaming]
    assert(streaming.enabled)
  }

  test("DefaultsTest - Stream disabled, streamAfter Set") {
    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.methodAndHost
                  |maxInitialLineKB: 4
                  |streamingEnabled: false
                  |servers:
                  |- port: 5000
      """.stripMargin
    val config = parse(yaml)

    val streaming = config.routerParams(Stack.Params.empty)[Streaming]
    assert(streaming.disabled)
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

  test("identifier gets router params") {
    import TestIdentifierInitializer._

    val yaml = s"""
                  |protocol: http
                  |identifier:
                  |  kind: io.l5d.test
                  |servers:
                  |- port: 5000
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HttpInitializer), Seq(TestIdentifierInitializer)))
    val config = mapper.readValue[RouterConfig](yaml).asInstanceOf[HttpConfig]

    // Scala doesn't automatically pick up this implicit for some reason.
    // val params = Stack.Params.empty + Test("foo")
    val params = Stack.Params.empty.+(Test("foo"))(param)
    val identifier = config.routerParams(params)[Http.param.HttpIdentifier]
      .id(Path.read("/svc"), () => Dtab.empty)
    assert(
      await(identifier(Request())).asInstanceOf[IdentifiedRequest[Request]].dst.path ==
        Path.read("/foo")
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

class TestIdentifierInitializer extends IdentifierInitializer {
  override def configClass = classOf[TestIdentifierConfig]
  override val configId = "io.l5d.test"
}
object TestIdentifierInitializer extends TestIdentifierInitializer {
  case class Test(value: String)
  implicit val param = new Stack.Param[Test] {
    override def default = Test("default")
  }
}

case class TestIdentifierConfig() extends HttpIdentifierConfig {
  import TestIdentifierInitializer._

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab,
    routerParams: Stack.Params
  ): Identifier[Request] = {
    println("PARAMS")
    routerParams.foreach(println)
    val value = routerParams[Test].value
    req => Future.value(new IdentifiedRequest(Dst.Path(Path.read(s"/$value")), req))
  }
}
