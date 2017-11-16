package io.buoyant.namerd.storage

import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namerd.DtabStoreConfig
import io.buoyant.test.FunSuite
import org.scalatest.OptionValues

class K8sConfigTest extends FunSuite with OptionValues {
  test("parse config") {
    val yaml =
      """
      |kind: io.l5d.k8s
      |host: localhost
      |port: 8001
      |namespace: default
    """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(K8sDtabStoreInitializer)))
    val k8s = mapper.readValue[DtabStoreConfig](yaml).asInstanceOf[K8sConfig]
    assert(k8s.host.value == "localhost")
    assert(k8s.port.value == Port(8001))
    assert(k8s.namespace.value == "default")
  }
}
