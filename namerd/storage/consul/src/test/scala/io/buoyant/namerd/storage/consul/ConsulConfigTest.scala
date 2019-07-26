package io.buoyant.namerd.storage.consul

import com.twitter.finagle.buoyant.{ClientAuth, TlsClientConfig}
import com.twitter.finagle.{Path, Stack}
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import io.buoyant.consul.v1.ConsistencyMode
import io.buoyant.namerd.DtabStoreConfig
import org.scalatest.{FunSuite, OptionValues}

class ConsulConfigTest extends FunSuite with OptionValues {
  test("sanity") {
    val store = ConsulConfig(None, None, Some(Path.read("/foo/bar"))).mkDtabStore(Stack.Params.empty)
  }

  test("parse config") {
    val yaml =
      """|kind: io.l5d.consul
         |experimental: true
         |pathPrefix: /foo/bar
         |host: consul.local
         |port: 80
         |token: some-token
         |datacenter: us-east-42
         |readConsistencyMode: stale
         |writeConsistencyMode: consistent
         |enableValueCompression: true
         |tls:
         |  disableValidation: false
         |  commonName: consul.io
         |  trustCertsBundle: /certificates/cacert-bundle.pem
         |  clientAuth:
         |    certPath: /certificates/cert.pem
         |    keyPath: /certificates/key.pem
      """.stripMargin
    val mapper = Parser.objectMapper(yaml, Iterable(Seq(ConsulDtabStoreInitializer)))
    val consul = mapper.readValue[DtabStoreConfig](yaml).asInstanceOf[ConsulConfig]
    assert(consul.host.value == "consul.local")
    assert(consul.port.value == Port(80))
    assert(consul.pathPrefix == Some(Path.read("/foo/bar")))
    assert(consul.token == Some("some-token"))
    assert(consul.datacenter == Some("us-east-42"))
    assert(consul.readConsistencyMode == Some(ConsistencyMode.Stale))
    assert(consul.writeConsistencyMode == Some(ConsistencyMode.Consistent))
    assert(consul.enableValueCompression == Some(true))
    val clientAuth = ClientAuth("/certificates/cert.pem", None, "/certificates/key.pem")
    val tlsConfig = TlsClientConfig(None, Some(false), Some("consul.io"), None, Some("/certificates/cacert-bundle.pem"), Some(clientAuth))
    assert(consul.tls == Some(tlsConfig))
  }

}
