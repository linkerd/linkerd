package io.l5d.experimental

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.config.Parser
import org.scalatest.FunSuite
import org.scalatest.OptionValues

class K8sTest extends FunSuite with OptionValues {

  test("sanity") {
    // ensure it doesn't totally blowup
    k8s(None, None, None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[K8sInitializer]))
  }

  test("parse config") {
    val host = "k8s-master.site.biz"
    val yaml = s"""
                  |kind: io.l5d.experimental.k8s
                  |host: $host
                  |port: 80
                  |tls: true
                  |authTokenFile: ../auth.token
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(K8sInitializer)))
    val k8s = mapper.readValue[NamerConfig](yaml).asInstanceOf[k8s]
    assert(k8s.host.value === host)
    assert(k8s.port.value === Port(80))
    val module = k8s.tls.value.tlsClientPrep(k8s.host.value)
    assert(module.peerCommonName(Stack.Params.empty) === Some(host))
    assert(k8s.authTokenFile.contains("../auth.token"))
  }

  test("parse config with full tls specification") {
    val yaml = s"""
                  |kind: io.l5d.experimental.k8s
                  |host: k8s-master.site.biz
                  |port: 80
                  |tls:
                  |  enabled: true
                  |  strict: true
                  |  caCertPath: ../ca.crt
                  |authTokenFile: ../auth.token
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(K8sInitializer)))
    val k8s = mapper.readValue[NamerConfig](yaml).asInstanceOf[k8s]
    assert(k8s.tls.isDefined)
  }

}
