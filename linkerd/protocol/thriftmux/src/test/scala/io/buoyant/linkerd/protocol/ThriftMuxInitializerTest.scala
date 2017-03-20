package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.Thrift.param
import com.twitter.finagle.Thrift.param.AttemptTTwitterUpgrade
import io.buoyant.linkerd.Linker
import io.buoyant.router.Thrift.param.MethodInDst
import io.buoyant.test.Exceptions
import org.apache.thrift.protocol.TCompactProtocol
import org.scalatest.FunSuite

class ThriftMuxInitializerTest extends FunSuite with Exceptions {

  test("valid thriftmux config") {
    val config = """
       |routers:
       |- protocol: thriftmux
       |  experimental: true
       |  thriftMethodInDst: true
       |  client:
       |    thriftFramed: false
       |    thriftProtocol: binary
       |    attemptTTwitterUpgrade: false
       |  servers:
       |  - thriftFramed: true
       |    thriftProtocol: compact
     """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftMuxInitializer)).load(config)
    val router = linker.routers.head
    assert(router.params[MethodInDst].enabled)
    assert(!router.params[param.Framed].enabled)
    assert(!router.params[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(router.servers.head.params[param.Framed].enabled)
    assert(router.servers.head.params[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(!router.params[AttemptTTwitterUpgrade].upgrade)
  }

  test("unsupported thrift protocol") {
    val config = """
       |routers:
       |- protocol: thriftmux
       |  experimental: true
       |  servers:
       |    thriftProtocol: magic
     """.stripMargin

    assertThrows[JsonMappingException] {
      Linker.Initializers(Seq(ThriftMuxInitializer)).load(config)
    }
  }

  test("attemptTTwitterUpgrade defaults false") {
    val config = """
       |routers:
       |- protocol: thriftmux
       |  experimental: true
       |  servers:
       |  - thriftFramed: true
       |    thriftProtocol: compact
     """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftMuxInitializer)).load(config)
    val router = linker.routers.head
    assert(!router.params[AttemptTTwitterUpgrade].upgrade)
  }
}

