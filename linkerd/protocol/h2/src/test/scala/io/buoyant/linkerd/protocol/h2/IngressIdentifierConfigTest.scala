package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.Stack.Params
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import org.scalatest.FunSuite

class IngressIdentifierConfigTest extends FunSuite {
  test("sanity") {
    val ingressConfig = new IngressIdentifierConfig(Some("example.org"), Some(Port(9090)), None, Some("linkerd-ingress"), Some(false))
    val _ = ingressConfig.newIdentifier(Params.empty)
  }

  test("parse simplest config") {
    val yaml =
      """|kind: io.l5d.ingress
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IngressIdentifierInitializer)))
    val ingress = mapper.readValue[IngressIdentifierConfig](yaml)
    assert(ingress.namespace == None)
    assert(ingress.ingressClassAnnotation == None)
    assert(ingress.ignoreDefaultBackends == None)
  }

  test("parse config with namespace") {
    val yaml =
      """|kind: io.l5d.ingress
         |namespace: "istio-ns"
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IngressIdentifierInitializer)))
    val ingress = mapper.readValue[IngressIdentifierConfig](yaml)
    assert(ingress.namespace == Some("istio-ns"))
    assert(ingress.ingressClassAnnotation == None)
  }

  test("parse config with namespace and ingress class") {
    val yaml =
      """|kind: io.l5d.ingress
         |ingressClassAnnotation: "istio"
         |namespace: "istio-ns"
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IngressIdentifierInitializer)))
    val ingress = mapper.readValue[IngressIdentifierConfig](yaml)
    assert(ingress.namespace == Some("istio-ns"))
    assert(ingress.ingressClassAnnotation == Some("istio"))
  }

  test("parse config for ignoreDefaultBackends ingress") {
    val yaml =
      """|kind: io.l5d.ingress
         |ignoreDefaultBackends: true
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(IngressIdentifierInitializer)))
    val ingress = mapper.readValue[IngressIdentifierConfig](yaml)
    assert(ingress.namespace == None)
    assert(ingress.ingressClassAnnotation == None)
    assert(ingress.ignoreDefaultBackends == Some(true))
  }
}
