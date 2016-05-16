package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import org.scalatest.FunSuite

class DefaultIdentifierConfigTest extends FunSuite {
  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = new DefaultIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[DefaultIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: default
                  |httpUriInDst: true
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(DefaultIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[DefaultIdentifierConfig]
    assert(config.httpUriInDst == Some(true))
  }

}
