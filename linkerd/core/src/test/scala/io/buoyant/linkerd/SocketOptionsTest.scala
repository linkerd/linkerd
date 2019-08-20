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
    val options = SocketOptionsConfig(reusePort = Some(true)).params

    assert(params == options)
  }

  test("socket options should keep default values if not specified") {
    val config =
      """
        |routers:
        |- protocol: plain
        |  client:
        |    socketOptions:
        |      backlog: 128
        |  servers:
        |  - {}
      """.stripMargin
    val linker = Linker.load(config, Initializers(protocol = Seq(TestProtocol.Plain)))
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    val options = SocketOptionsConfig(
      noDelay = Some(true),
      reuseAddr = Some(true),
      reusePort = Some(false),
      backlog = Some(128)).params

    assert(params == options)
  }
}
