package io.buoyant.linkerd.telemeter

import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.telemetry.{TelemeterConfig, TelemeterInitializer}
import org.scalatest.FunSuite

class UsageDataTelemeterTest extends FunSuite {

  test("sanity") {
    val _ = UsageDataTelemeterConfig(None, None)
  }

  test("service registration") {
    assert(LoadService[TelemeterInitializer]().exists(_.isInstanceOf[UsageDataTelemeterInitializer]))
  }

  test("parse config") {
    val yaml = s"""
                  |kind: io.l5d.usage
                  |orgId: parakeet
                  |dryRun: true
      """.stripMargin

    val mapper = Parser.objectMapper(yaml, Iterable(Seq(UsageDataTelemeterInitializer)))
    val usage = mapper.readValue[TelemeterConfig](yaml).asInstanceOf[UsageDataTelemeterConfig]
    assert(usage.orgId == Some("parakeet"))
    assert(usage.dryRun == Some(true))
  }
}
