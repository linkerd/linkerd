package io.buoyant.namer.k8s

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class K8sTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    K8sConfig(None, None, None).newNamer(Stack.Params.empty)
    val _ = K8sNamespacedConfig(None, None, None, None)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[K8sInitializer]))
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[K8sNamespacedInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.k8s
                  |host: k8s-master.site.biz
                  |port: 80
                  |labelSelector: version
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(K8sInitializer)))
    val k8s = mapper.readValue[NamerConfig](yaml).asInstanceOf[K8sConfig]
    assert(k8s.host == Some("k8s-master.site.biz"))
    assert(k8s.port == Some(Port(80)))
    assert(k8s.labelSelector == Some("version"))
    assert(!k8s.disabled)
  }

  test("parse namespaced config") {
    val yaml = s"""
                  |kind: io.l5d.k8s.ns
                  |envVar: MY_NAMESPACE
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(K8sNamespacedInitializer)))
    val k8s = mapper.readValue[NamerConfig](yaml).asInstanceOf[K8sNamespacedConfig]
    assert(k8s.envVar == Some("MY_NAMESPACE"))
  }
}
