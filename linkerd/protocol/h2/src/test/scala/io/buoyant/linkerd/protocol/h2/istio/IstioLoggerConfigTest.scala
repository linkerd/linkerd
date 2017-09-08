package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.linkerd.LoggerInitializer
import io.buoyant.linkerd.protocol.h2.H2LoggerConfig
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IstioLoggerConfigTest extends FunSuite with Awaits {
  test("sanity") {
    // ensure it doesn't totally blow up
    val _ = new IstioLoggerConfig(None, None).mk(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[LoggerInitializer].exists(_.isInstanceOf[IstioLoggerInitializer]))
  }

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.k8s.istio
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IstioLoggerInitializer)))
    val config = mapper.readValue[H2LoggerConfig](yaml).asInstanceOf[IstioLoggerConfig]
    val logger = config.mk(Stack.Params.empty)
    assert(logger.isInstanceOf[IstioLogger])
  }

  test("overrides defaults") {
    val yaml =
      s"""|kind: io.l5d.k8s.istio
          |mixerHost: mixerHost
          |mixerPort: 1234
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IstioLoggerInitializer)))
    val config = mapper.readValue[H2LoggerConfig](yaml).asInstanceOf[IstioLoggerConfig]
    assert(config.mixerHost == Some("mixerHost"))
    assert(config.mixerPort == Some(Port(1234)))
  }
}
