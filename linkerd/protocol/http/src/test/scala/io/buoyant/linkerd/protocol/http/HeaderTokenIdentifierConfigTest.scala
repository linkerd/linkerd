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

class HeaderTokenIdentifierConfigTest extends FunSuite with Awaits {
  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = new HeaderTokenIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[HeaderTokenIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.header.token
                  |header: my-header
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HeaderTokenIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[HeaderTokenIdentifierConfig]
    val identifier = config.newIdentifier(Path.empty)
    val req = Request()
    req.headerMap.set("my-header", "foo")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/foo"))
    )
  }

  test("default header") {
    val yaml = s"""
                  |kind: io.l5d.header.token
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HeaderTokenIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[HeaderTokenIdentifierConfig]
    val identifier = config.newIdentifier(Path.empty)
    val req = Request()
    req.headerMap.set("Host", "foo")
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/foo"))
    )
  }
}
