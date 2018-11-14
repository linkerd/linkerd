package io.buoyant.namerd.iface

import com.twitter.finagle.buoyant.SocketOptionsConfig
import io.buoyant.config.Parser
import org.scalatest.FunSuite

class HttpControlServiceConfigTest extends FunSuite {

  test("address") {
    val yaml = """
      |kind: io.l5d.httpController
      |ip: 1.2.3.4
      |port: 1234
    """.stripMargin

    val config = Parser
      .objectMapper(
        yaml,
        Iterable(Seq(new HttpControlServiceInitializer))
      ).readValue[HttpControlServiceConfig](yaml)

    assert(config.addr.getHostString == "1.2.3.4")
    assert(config.addr.getPort == 1234)
  }

  test("tls") {
    val yaml = """
      |kind: io.l5d.httpController
      |tls:
      |  certPath: cert.pem
      |  keyPath: key.pem
      |  caCertPath: cacert.pem
      |  ciphers:
      |  - "foo"
      |  - "bar"
      |  requireClientAuth: true
    """.stripMargin

    val config = Parser
      .objectMapper(
        yaml,
        Iterable(Seq(new HttpControlServiceInitializer))
      ).readValue[HttpControlServiceConfig](yaml)

    val tls = config.tls.get
    assert(tls.certPath == "cert.pem")
    assert(tls.keyPath == "key.pem")
    assert(tls.caCertPath == Some("cacert.pem"))
    assert(tls.ciphers == Some(List("foo", "bar")))
    assert(tls.requireClientAuth == Some(true))
  }

  test("socket options"){
    val expectedOpts = SocketOptionsConfig(reusePort = true, readTimeoutSeconds = Some(60), keepAlive = Some(true))
    val yaml = """
      |kind: io.l5d.httpController
      |socketOptions:
      |  noDelay: true
      |  reuseAddr: true
      |  reusePort: true
      |  readTimeoutSeconds: 60
      |  keepAlive: true
    """.stripMargin

    val config = Parser
      .objectMapper(yaml,
        Iterable(Seq(new HttpControlServiceInitializer))
      ).readValue[HttpControlServiceConfig](yaml)

    val sockOpts = config.socketOptions.get
    assert(sockOpts == expectedOpts)
  }
}
