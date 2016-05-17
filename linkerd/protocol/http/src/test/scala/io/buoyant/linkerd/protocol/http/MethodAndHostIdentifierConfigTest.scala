package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Path
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import org.scalatest.FunSuite

class MethodAndHostIdentifierConfigTest extends FunSuite {
  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = new MethodAndHostIdentifierConfig().newIdentifier(Path.empty)
  }

  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[MethodAndHostIdentifierInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.methodAndHost
                  |httpUriInDst: true
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(MethodAndHostIdentifierInitializer)))
    val config = mapper.readValue[HttpIdentifierConfig](yaml).asInstanceOf[MethodAndHostIdentifierConfig]
    assert(config.httpUriInDst == Some(true))
  }

}
