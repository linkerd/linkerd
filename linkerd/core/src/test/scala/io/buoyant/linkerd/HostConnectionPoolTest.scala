package io.buoyant.linkerd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.Path
import io.buoyant.linkerd.Linker.Initializers
import io.buoyant.router.StackRouter.Client.PerClientParams
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
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    val pool = params[DefaultPool.Param]
    assert(pool.low == 5)
    assert(pool.high == 100)
    assert(pool.idleTime == 5.seconds)
    assert(pool.maxWaiters == 10)
  }
}
