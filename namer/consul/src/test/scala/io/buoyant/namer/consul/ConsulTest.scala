package io.buoyant.namer.consul

import com.twitter.finagle.Stack
import com.twitter.finagle.buoyant.{ClientAuth, TlsClientConfig}
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.consul.v1.{ConsistencyMode, HealthStatus}
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import org.scalatest.FunSuite

class ConsulTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    val _ = ConsulConfig(None, None, None, None, None, None, None, None, None, None, None, None, None, None, None).newNamer(Stack.Params.empty)
  }

  test("service registration") {
    assert(LoadService[NamerInitializer]().exists(_.isInstanceOf[ConsulInitializer]))
  }

  test("parse minimal config") {
    val yaml = s"""
                  |kind: io.l5d.consul
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.setHost.isEmpty)
    assert(consul.includeTag.isEmpty)
    assert(!consul.disabled)
  }

  test("parse all options config") {
    val yaml = s"""
                    |kind: io.l5d.consul
                    |host: consul.site.biz
                    |port: 8600
                    |token: some-token
                    |includeTag: true
                    |useHealthCheck: true
                    |maxHeadersKB: 4
                    |maxInitialLineKB: 8
                    |maxRequestKB: 5192
                    |maxResponseKB: 5192
                    |healthStatuses:
                    | - warning
                    |setHost: true
                    |consistencyMode: stale
                    |failFast: true
                    |preferServiceAddress: false
                    |weights:
                    | - tag: primary
                    |   weight: 100
                    |tls:
                    |  disableValidation: false
                    |  commonName: consul.io
                    |  trustCertsBundle: /certificates/cacerts-bundle.pem
                    |  clientAuth:
                    |    certPath: /certificates/cert.pem
                    |    keyPath: /certificates/key.pem
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulInitializer)))
    val consul = mapper.readValue[NamerConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.host == Some("consul.site.biz"))
    assert(consul.port == Some(Port(8600)))
    assert(consul.token == Some("some-token"))
    assert(consul.useHealthCheck == Some(true))
    assert(consul.healthStatuses == Some(Set(HealthStatus.Warning)))
    assert(consul.setHost == Some(true))
    assert(consul.includeTag == Some(true))
    assert(consul.consistencyMode == Some(ConsistencyMode.Stale))
    assert(consul.failFast == Some(true))
    assert(consul.preferServiceAddress == Some(false))
    assert(consul.maxHeadersKB == Some(4))
    assert(consul.maxInitialLineKB == Some(8))
    assert(consul.maxRequestKB == Some(5192))
    assert(consul.maxResponseKB == Some(5192))
    assert(consul.weights == Some(Seq(TagWeight("primary", 100.0))))
    val clientAuth = ClientAuth("/certificates/cert.pem", None, "/certificates/key.pem")
    val tlsConfig = TlsClientConfig(None, Some(false), Some("consul.io"), None, Some("/certificates/cacerts-bundle.pem"), Some(clientAuth))
    assert(consul.tls == Some(tlsConfig))
    assert(!consul.disabled)
  }
}
