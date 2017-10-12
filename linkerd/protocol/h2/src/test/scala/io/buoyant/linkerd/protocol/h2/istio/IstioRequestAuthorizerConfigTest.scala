package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.linkerd.RequestAuthorizerInitializer
import io.buoyant.linkerd.protocol.h2.H2RequestAuthorizerConfig
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class IstioRequestAuthorizerConfigTest extends FunSuite with Awaits {
  test("sanity") {
    // ensure it doesn't totally blow up
    val _ = new IstioRequestAuthorizerConfig(None, None).mk(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[RequestAuthorizerInitializer].exists(_.isInstanceOf[IstioRequestAuthorizerInitializer]))
  }

  test("parse config") {
    val yaml =
      s"""|kind: io.l5d.k8s.istio
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IstioRequestAuthorizerInitializer)))
    val config = mapper.readValue[H2RequestAuthorizerConfig](yaml).asInstanceOf[IstioRequestAuthorizerConfig]
    val logger = config.mk(Stack.Params.empty)
    assert(logger.isInstanceOf[IstioRequestAuthorizer])
  }

  test("overrides defaults") {
    val yaml =
      s"""|kind: io.l5d.k8s.istio
          |mixerHost: mixerHost
          |mixerPort: 1234
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IstioRequestAuthorizerInitializer)))
    val config = mapper.readValue[H2RequestAuthorizerConfig](yaml).asInstanceOf[IstioRequestAuthorizerConfig]
    assert(config.mixerHost == Some("mixerHost"))
    assert(config.mixerPort == Some(Port(1234)))
  }
}
