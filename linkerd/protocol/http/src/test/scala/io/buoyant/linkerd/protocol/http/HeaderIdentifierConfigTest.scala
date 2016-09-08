package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import org.scalatest.FunSuite

class HeaderIdentifierConfigTest extends FunSuite {
  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = new HeaderIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[HeaderIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.header
                  |header: my-header
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(HeaderIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[HeaderIdentifierConfig]
    assert(config.header == Some("my-header"))
  }

}
