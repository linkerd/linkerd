package io.buoyant.linkerd

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.SocketOptionsConfig
import io.buoyant.linkerd.Linker.Initializers
import io.buoyant.router.StackRouter.Client.PerClientParams
import org.scalatest.FunSuite

class SocketOptionsTest extends FunSuite {
  test("socket options") {
    val config =
      """
        |routers:
        |- protocol: plain
        |  client:
        |    socketOptions:
        |      noDelay: true
        |      reuseAddr: true
        |      reusePort: true
        |  servers:
        |  - {}
      """.stripMargin
    val linker = Linker.load(config, Initializers(protocol = Seq(TestProtocol.Plain)))
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    val options = SocketOptionsConfig(reusePort = true).params

    assert(params == options)
  }
}
