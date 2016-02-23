package io.l5d

import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.{NamerInitializer, NamerConfig}
import io.buoyant.linkerd.config.Parser
import org.scalatest.FunSuite

class ServersetsTest extends FunSuite {

  def parse(yaml: String): serversets = {
    val mapper = Parser.objectMapper(yaml)
    ServersetsInitializer.registerSubtypes(mapper)
    mapper.readValue[NamerConfig](yaml).asInstanceOf[serversets]
  }

  test("zkHost list") {
    val yaml = """
kind: io.l5d.serversets
zkAddrs:
- host: foo
  port: 2181
- host: bar
  port: 2182
"""
    assert(parse(yaml).connectString == "foo:2181,bar:2182")
  }

  test("single zkHost") {
    val yaml = """
kind: io.l5d.serversets
zkAddrs:
- host: foo
  port: 2181
"""
    assert(parse(yaml).connectString == "foo:2181")
  }

  test("missing hostname") {
    val yaml = """
kind: io.l5d.serversets
zkAddrs:
- port: 2181
"""
    assert(
      intercept[JsonMappingException](parse(yaml))
      .getCause.isInstanceOf[IllegalArgumentException]
    )
  }

  test("default port") {
    val yaml = """
kind: io.l5d.serversets
zkAddrs:
- host: foo
"""
    assert(parse(yaml).connectString == "foo:2181")
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ServersetsInitializer]))
  }
}
