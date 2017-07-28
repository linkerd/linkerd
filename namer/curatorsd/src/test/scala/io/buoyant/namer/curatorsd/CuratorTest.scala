package io.buoyant.namer.curatorsd

import com.fasterxml.jackson.databind.JsonMappingException
import com.medallia.l5d.curatorsd.namer.{CuratorSDConfig, CuratorSDInitializer}
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.test.Exceptions
import org.scalatest.FunSuite

class CuratorTest extends FunSuite with Exceptions {

  def parse(yaml: String): CuratorSDConfig = {
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(CuratorSDInitializer)))
    mapper.readValue[NamerConfig](yaml).asInstanceOf[CuratorSDConfig]
  }

  test("zkConnectStr list") {
    val yaml = """
kind: com.medallia.curatorsd
zkConnectStr: foo:2181,bar:2181/chroot
"""
    assert(parse(yaml).zkConnectStr == "foo:2181,bar:2181/chroot")
  }

  // TODO
//  test("missing hostname") {
//    val yaml = """
//kind: com.medallia.curatorsd
//"""
//    assertThrows[JsonMappingException](parse(yaml))
//  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[CuratorSDInitializer]))
  }
}
