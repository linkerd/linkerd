package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HeaderIdentifierConfigTest extends FunSuite with Awaits {
  test("sanity") {
    // ensure it doesn't totally blow up
    val _ = new HeaderIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[HeaderIdentifierInitializer]))
  }

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.header
          |header: my-header
          |""".stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HeaderIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[HeaderIdentifierConfig]
    val identifier = config.newIdentifier(Path.empty)
    val req = Request()
    req.headerMap.set("my-header", "/one/two/three")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/one/two/three"))
    )
  }

  test("default header") {
    val yaml = s"""
                  |kind: io.l5d.header
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HeaderIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[HeaderIdentifierConfig]
    val identifier = config.newIdentifier(Path.empty)
    val req = Request()
    req.headerMap.set("l5d-name", "/one/two/three")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/one/two/three"))
    )
  }
}
