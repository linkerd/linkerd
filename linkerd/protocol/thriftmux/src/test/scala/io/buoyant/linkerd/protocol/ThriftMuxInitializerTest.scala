package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.Path
import com.twitter.finagle.Thrift.param
import io.buoyant.linkerd.Linker
import io.buoyant.router.StackRouter.Client.PerClientParams
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
       |  thriftProtocol: compact
       |  thriftMethodInDst: true
       |  client:
       |    thriftFramed: false
       |    attemptTTwitterUpgrade: false
       |  servers:
       |  - thriftFramed: true
     """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftMuxInitializer)).load(config)
    val router = linker.routers.head
    val routerParams = router.params
    val serverParams = router.servers.head.params
    val clientParams = router.params[PerClientParams].paramsFor(Path.read("/foo"))
    assert(routerParams[MethodInDst].enabled)
    assert(routerParams[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(serverParams[param.Framed].enabled)
    assert(serverParams[param.ProtocolFactory].protocolFactory.isInstanceOf[TCompactProtocol.Factory])
    assert(!clientParams[param.Framed].enabled)
    assert(!clientParams[param.AttemptTTwitterUpgrade].upgrade)
  }

  test("unsupported thrift protocol") {
    val config = """
       |routers:
       |- protocol: thriftmux
       |  experimental: true
       |  thriftProtocol: magic
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
       |  thriftProtocol: compact
       |  servers:
       |  - thriftFramed: true
     """.stripMargin

    val linker = Linker.Initializers(Seq(ThriftMuxInitializer)).load(config)
    val router = linker.routers.head
    val params = router.params[PerClientParams].paramsFor(Path.read("/foo"))
    assert(!params[param.AttemptTTwitterUpgrade].upgrade)
  }
}
