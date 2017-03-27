package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.Path
import com.twitter.finagle.Thrift.param
import com.twitter.finagle.Thrift.param.AttemptTTwitterUpgrade
import io.buoyant.linkerd.Linker
import io.buoyant.router.StackRouter.Client.PerClientParams
import io.buoyant.router.Thrift.param.MethodInDst
import io.buoyant.test.Exceptions
import org.apache.thrift.protocol.TCompactProtocol
import org.scalatest.FunSuite

class ThriftInitializerTest extends FunSuite with Exceptions {

  test("valid thrift config") {
    val config = """
      |routers:
      |- protocol: thrift
      |  thriftMethodInDst: true
      |  client:
      |    thriftFramed: false
      |    thriftProtocol: binary
      |    attemptTTwitterUpgrade: false
      |  servers:
      |  - thriftFramed: true
      |    thriftProtocol: compact
    """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftInitializer)).load(config)
    val router = linker.routers.head
    val params = router.params[PerClientParams].paramsFor(Path.read("/foo"))
    assert(router.params[MethodInDst].enabled)
    assert(!params[param.Framed].enabled)
    assert(!params[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(router.servers.head.params[param.Framed].enabled)
    assert(router.servers.head.params[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(!params[AttemptTTwitterUpgrade].upgrade)
  }

  test("unsupported thrift protocol") {
    val config = """
      |routers:
      |- protocol: thrift
      |  servers:
      |    thriftProtocol: magic
    """.stripMargin

    assertThrows[JsonMappingException] {
      Linker.Initializers(Seq(ThriftInitializer)).load(config)
    }
  }

  test("attemptTTwitterUpgrade defaults false") {
    val config = """
                   |routers:
                   |- protocol: thrift
                   |  servers:
                   |  - thriftFramed: true
                   |    thriftProtocol: compact
                 """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftInitializer)).load(config)
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    assert(!params[AttemptTTwitterUpgrade].upgrade)
  }
}
