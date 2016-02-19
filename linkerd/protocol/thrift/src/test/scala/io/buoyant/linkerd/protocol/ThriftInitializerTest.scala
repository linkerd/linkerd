package io.buoyant.linkerd.protocol

import com.twitter.finagle.Thrift.param
import io.buoyant.linkerd.Linker
import io.buoyant.router.Thrift.param.MethodInDst
import org.scalatest.FunSuite

class ThriftInitializerTest extends FunSuite {

  test("thrift config") {

    val config = """
      |routers:
      |- protocol: thrift
      |  thriftMethodInDst: true
      |  client:
      |    thriftFramed: false
      |  servers:
      |  - thriftFramed: false
    """.stripMargin

    val linker = Linker.load(config, Seq(ThriftInitializer))
    val router = linker.routers.head
    assert(router.params[MethodInDst].enabled)
    assert(!router.params[param.Framed].enabled)
    assert(!router.servers.head.params[param.Framed].enabled)
  }
}
