package io.buoyant.namerd.iface

import com.twitter.finagle.buoyant.SocketOptionsConfig
import io.buoyant.config.Parser
import org.scalatest.FunSuite

class ThriftInterpreterInterfaceConfigTest extends FunSuite {

  test("cache capacity") {
    val yaml = """
                 |kind: io.l5d.thriftNameInterpreter
                 |cache:
                 |  bindingCacheActive: 5000
                 |  bindingCacheInactive: 1000
                 |  bindingCacheInactiveTTLSecs: 3600
                 |  addrCacheActive: 6000
               """.stripMargin

    val config = Parser
      .objectMapper(
        yaml,
        Iterable(Seq(new ThriftInterpreterInterfaceInitializer))
      ).readValue[ThriftInterpreterInterfaceConfig](yaml)

    val capacity = config.cache.get.capacity
    assert(capacity.bindingCacheActive == 5000)
    assert(capacity.bindingCacheInactive == 1000)
    assert(capacity.bindingCacheInactiveTTLSecs == 3600)
    assert(capacity.addrCacheActive == 6000)
    assert(capacity.addrCacheInactive == ThriftNamerInterface.Capacity.default.addrCacheInactive)
    assert(
      capacity.addrCacheInactiveTTLSecs == ThriftNamerInterface.Capacity.default
        .addrCacheInactiveTTLSecs
    )
  }

  test("tls") {
    val yaml = """
                 |kind: io.l5d.thriftNameInterpreter
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
        Iterable(Seq(new ThriftInterpreterInterfaceInitializer))
      ).readValue[ThriftInterpreterInterfaceConfig](yaml)

    val tls = config.tls.get
    assert(tls.certPath == "cert.pem")
    assert(tls.keyPath == "key.pem")
    assert(tls.caCertPath == Some("cacert.pem"))
    assert(tls.ciphers == Some(List("foo", "bar")))
    assert(tls.requireClientAuth == Some(true))
  }

  test("read socket options") {
    val expectedOpts = SocketOptionsConfig(reusePort = Some(true))
    val yaml = s"""
        |kind: io.l5d.thriftNameInterpreter
        |ip: 0.0.0.0
        |port: 8085
        |socketOptions:
        |  noDelay: true
        |  reuseAddr: true
        |  reusePort: true
     """.stripMargin

    val config = Parser.objectMapper(yaml, Iterable(Seq(new ThriftInterpreterInterfaceInitializer)))
      .readValue[ThriftInterpreterInterfaceConfig](yaml)
    config.socketOptions match {
      case None => fail(s"socket options is None. Expected $expectedOpts")
      case Some(opts) => assert(opts == expectedOpts)
    }
  }
}
