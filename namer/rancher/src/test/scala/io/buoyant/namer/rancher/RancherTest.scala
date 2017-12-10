package io.buoyant.namer.rancher

import com.twitter.finagle._
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.test.FunSuite
import org.scalatest.Matchers

class RancherTest extends FunSuite with Matchers {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = RancherConfig(
      portMappings = None
    ).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[RancherInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.rancher
                  |experimental: true
                  |portMappings:
                  |  proxy: 8080
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(RancherInitializer)))
    val config = mapper.readValue[NamerConfig](yaml).asInstanceOf[RancherConfig]
    assert(config.portMappings === Some(Map("proxy" -> 8080)))
    assert(config.disabled === false)
  }
}
