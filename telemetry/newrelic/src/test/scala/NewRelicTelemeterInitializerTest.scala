package io.buoyant.telemetry.newrelic

import java.net.InetAddress
import com.fasterxml.jackson.databind.JsonMappingException
import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.telemetry.{TelemeterConfig, TelemeterInitializer}
import io.buoyant.test.FunSuite

class NewRelicTelemeterInitializerTest extends FunSuite {

  test("io.l5d.newrelic telemeter loads") {
    val yaml =
      """|kind: io.l5d.newrelic
         |license_key: foo
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[NewRelicTelemeter])
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }

  test("io.l5d.newrelic telemeter default host") {
    val yaml =
      """|kind: io.l5d.newrelic
         |license_key: foo
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[NewRelicTelemeter]
    assert(telemeter.host == InetAddress.getLocalHost.getCanonicalHostName)
  }

  test("io.l5d.newrelic telemeter license key") {
    val yaml =
      """|kind: io.l5d.newrelic
         |license_key: foo
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[NewRelicTelemeter]
    assert(telemeter.licenseKey === "foo")
  }

  test("io.l5d.newrelic telemeter default prefix") {
    val yaml =
      """|kind: io.l5d.newrelic
         |license_key: foo
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[NewRelicTelemeter]
    assert(telemeter.name === "Linkerd")
  }

  test("io.l5d.newrelic telemeter prefix") {
    val yaml =
      """|kind: io.l5d.newrelic
         |license_key: foo
         |prefix: some_prefix_
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[NewRelicTelemeter]
    assert(telemeter.name === "some_prefix_")
  }

  test("io.l5d.newrelic telemeter throws an exception with no license key") {
    val yaml =
      """|kind: io.l5d.newrelic
         |""".stripMargin

    assertThrows[JsonMappingException] {
      val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
        .readValue[TelemeterConfig](yaml)

      val telemeter = config.mk(Stack.Params.empty).asInstanceOf[NewRelicTelemeter]
    }
  }
}
