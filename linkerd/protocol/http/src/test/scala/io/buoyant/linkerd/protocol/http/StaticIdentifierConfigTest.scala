package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.IdentifiedRequest
import io.buoyant.test.FunSuite

class StaticIdentifierConfigTest extends FunSuite {
  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = StaticIdentifierConfig(Path.read("/foo")).newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[StaticIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.static
                  |path: /foo
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(StaticIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[StaticIdentifierConfig]
    val identifier = config.newIdentifier(Path.empty)
    val req = Request()
    assert(
      await(identifier(req)).asInstanceOf[IdentifiedRequest[Request]].dst ==
        Dst.Path(Path.read("/foo"))
    )
  }
}
