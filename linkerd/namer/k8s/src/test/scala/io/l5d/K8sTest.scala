package io.l5d.experimental

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.config.types.Port
import io.buoyant.linkerd.{NamerConfig, NamerInitializer}
import io.buoyant.linkerd.config.Parser
import org.scalatest.FunSuite

class K8sTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    k8s(None, None, None, None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[K8sInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.experimental.k8s
                  |host: k8s-master.site.biz
                  |port: 80
                  |tls: false
                  |authTokenFile: ../auth.token
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Seq(K8sInitializer))
    val k8s = mapper.readValue[NamerConfig](yaml).asInstanceOf[k8s]
    assert(k8s.host == Some("k8s-master.site.biz"))
    assert(k8s.port == Some(Port(80)))
    assert(k8s.tls == Some(false))
    assert(k8s.authTokenFile == Some("../auth.token"))
  }
}
