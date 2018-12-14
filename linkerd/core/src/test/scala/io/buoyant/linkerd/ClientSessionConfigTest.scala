package io.buoyant.linkerd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Path
import com.twitter.finagle.service.ExpiringService
import com.twitter.util.Duration
import io.buoyant.linkerd.Linker.Initializers
import io.buoyant.router.StackRouter.Client.PerClientParams
import org.scalatest.FunSuite

class ClientSessionConfigTest extends FunSuite {

  test("expiring service params") {
    // Arrange
    val config = """
                   |routers:
                   |- protocol: plain
                   |  client:
                   |    clientSession:
                   |      idleTimeMs: 5000
                   |      lifeTimeMs: 7000
                   |  servers:
                   |  - {}
                   |""".stripMargin

    // Act
    val linker = Linker.load(config, Initializers(protocol = Seq(TestProtocol.Plain)))
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    val result = params[ExpiringService.Param]

    // Assert
    assert(result.idleTime == 5.seconds)
    assert(result.lifeTime == 7.seconds)
  }

  test("expiring service empty idle param") {
    // Arrange
    val config = """
                   |routers:
                   |- protocol: plain
                   |  client:
                   |    clientSession:
                   |      lifeTimeMs: 7000
                   |  servers:
                   |  - {}
                   |""".stripMargin

    // Act
    val linker = Linker.load(config, Initializers(protocol = Seq(TestProtocol.Plain)))
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    val result = params[ExpiringService.Param]

    // Assert
    assert(result.idleTime == Duration.Top)
    assert(result.lifeTime == 7.seconds)
  }

  test("expiring service empty lifetime param") {
    // Arrange
    val config = """
                   |routers:
                   |- protocol: plain
                   |  client:
                   |    clientSession:
                   |      idleTimeMs: 5000
                   |  servers:
                   |  - {}
                   |""".stripMargin

    // Act
    val linker = Linker.load(config, Initializers(protocol = Seq(TestProtocol.Plain)))
    val params = linker.routers.head.params[PerClientParams].paramsFor(Path.read("/foo"))
    val result = params[ExpiringService.Param]

    // Assert
    assert(result.idleTime == 5.seconds)
    assert(result.lifeTime == Duration.Top)
  }

}
