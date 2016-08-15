package io.buoyant.namerd.storage.consul

import com.twitter.finagle.Path
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namerd.DtabStoreConfig
import org.scalatest.{FunSuite, OptionValues}

class ConsulConfigTest extends FunSuite with OptionValues {
  test("sanity") {
    val store = ConsulConfig(None, None, Some(Path.read("/foo/bar"))).mkDtabStore
  }

  test("parse config") {
    val yaml =
      """|kind: io.l5d.consul
         |experimental: true
         |pathPrefix: /foo/bar
         |host: consul.local
         |port: 80
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulDtabStoreInitializer)))
    val consul = mapper.readValue[DtabStoreConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.host.value == "consul.local")
    assert(consul.port.value == Port(80))
    assert(consul.pathPrefix == Some(Path.read("/foo/bar")))
  }

}
