package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IstioIdentifierTest extends FunSuite with Awaits {
  test("service registration") {
    assert(LoadService[IdentifierInitializer].exists(_.isInstanceOf[IstioIdentifierInitializer]))
  }

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.k8s.istio
          |discoveryHost: myHost
          |discoveryPort: 9999
          |mixerPort: 8080
          |mixerHost: mixer-2
          |""".stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IstioIdentifierInitializer)))
    val config = mapper.readValue[H2IdentifierConfig](yaml).asInstanceOf[IstioIdentifierConfig]

    assert(config.discoveryHost == Some("myHost"))
    assert(config.discoveryPort == Some(Port(9999)))
    assert(config.mixerHost == Some("mixer-2"))
    assert(config.mixerPort == Some(Port(8080)))
  }
}

