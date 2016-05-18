package io.buoyant.namerd.storage.etcd

import com.twitter.finagle.Path
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namerd.DtabStoreConfig
import org.scalatest.{FunSuite, OptionValues}

class EtcdConfigTest extends FunSuite with OptionValues {
  test("sanity") {
    val store = EtcdConfig(None, None, Some(Path.read("/foo/bar"))).mkDtabStore
  }

  test("parse config") {
    val yaml =
      """|kind: io.l5d.etcd
         |experimental: true
         |pathPrefix: /foo/bar
         |host: etcd.dentist
         |port: 80
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(EtcdDtabStoreInitializer)))
    val etcd = mapper.readValue[DtabStoreConfig](yaml).asInstanceOf[EtcdConfig]
    assert(etcd.host.value == "etcd.dentist")
    assert(etcd.port.value == Port(80))
    assert(etcd.pathPrefix == Some(Path.read("/foo/bar")))
  }

}
