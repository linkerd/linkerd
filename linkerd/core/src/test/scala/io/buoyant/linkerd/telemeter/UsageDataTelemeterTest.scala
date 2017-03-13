package io.buoyant.linkerd
package telemeter

import io.buoyant.namer.{NamerInitializer, TestNamerInitializer}
import io.buoyant.telemetry.{TelemeterInitializer, TestTelemeterInitializer}
import io.buoyant.test.FunSuite

class UsageDataTelemeterTest extends FunSuite {

  def initializer(
    protos: Seq[ProtocolInitializer] = Seq(TestProtocol.Plain, TestProtocol.Fancy),
    namers: Seq[NamerInitializer] = Seq(TestNamerInitializer),
    telemeters: Seq[TelemeterInitializer] = Seq(new TestTelemeterInitializer)
  ) = Linker.Initializers(protocol = protos, namer = namers, telemetry = telemeters)

  def parse(yaml: String) = initializer().load(yaml)

  test("usage telemeter is enabled by default") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |""".stripMargin

    val linker = parse(yaml)
    assert(linker.telemeters.exists(_.isInstanceOf[UsageDataTelemeter]))
  }

  test("usage telemeter can be configured") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |usage:
         |  orgId: my-org
         |""".stripMargin

    val linker = parse(yaml)
    val orgId = linker.telemeters.collectFirst {
      case t: UsageDataTelemeter => t.orgId
    }.flatten
    assert(orgId == Some("my-org"))
  }

  test("usage telemeter can be disabled") {
    val yaml =
      """|routers:
         |- protocol: plain
         |  servers:
         |  - port: 1
         |usage:
         |  enabled: false
         |""".stripMargin

    val linker = parse(yaml)
    assert(!linker.telemeters.exists(_.isInstanceOf[UsageDataTelemeter]))
  }

}
