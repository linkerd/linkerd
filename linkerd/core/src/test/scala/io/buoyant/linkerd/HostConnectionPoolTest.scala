package io.buoyant.linkerd

import com.twitter.conversions.time._
import com.twitter.finagle.client.DefaultPool
import io.buoyant.linkerd.Linker.Initializers
import org.scalatest.FunSuite

class HostConnectionPoolTest extends FunSuite {

  test("pool size") {
    val config = """
                   |routers:
                   |- protocol: plain
                   |  client:
                   |    hostConnectionPool:
                   |      minSize: 5
                   |      maxSize: 100
                   |      idleTimeMs: 5000
                   |      maxWaiters: 10
                   |  servers:
                   |  - {}
                   |""".stripMargin

    val linker = Linker.load(config, Initializers(protocol = Seq(TestProtocol.Plain)))
    val pool = linker.routers.head.params[DefaultPool.Param]
    assert(pool.low == 5)
    assert(pool.high == 100)
    assert(pool.idleTime == 5.seconds)
    assert(pool.maxWaiters == 10)
  }
}
