package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import org.scalatest.FunSuite

class PathIdentifierConfigTest extends FunSuite {
  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = new PathIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[PathIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.path
                  |segments: 2
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(PathIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[PathIdentifierConfig]
    assert(config.segments == Some(2))
  }

}
