package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.service.Backoff
import com.twitter.util.Duration
import io.buoyant.config.Parser
import io.buoyant.linkerd.{ConstantBackoffConfig, FailureAccrualConfig, JitteredBackoffConfig}
import io.buoyant.test.FunSuite
import org.scalatest.{Matchers, OptionValues, OutcomeOf}
import com.twitter.conversions.time._
import org.scalatest.prop.TableDrivenPropertyChecks

/**
 * Created by eliza on 6/27/17.
 */
class ConfigTest extends FunSuite
  with Matchers
  with OptionValues
  with TableDrivenPropertyChecks
  with OutcomeOf {

  def parse(yaml: String): FailureAccrualConfig = {
    val mapper = Parser.objectMapper(yaml, Seq(Seq(
      new ConsecutiveFailuresInitializer,
      new NoneInitializer,
      new SuccessRateInitializer,
      new SuccessRateWindowedInitializer
    )))
    mapper.readValue[FailureAccrualConfig](yaml)
  }

  val kinds =
    Table(
      ("kind"),
      ("kind: io.l5d.consecutiveFailures"),
      ("kind: io.l5d.successRate"),
      ("kind: io.l5d.successRateWindowed"),
      ("kind: none")
    )

  val backoffs =
    Table(
      ("config", "backoff"),
      ("  kind: constant\n  ms: 10000", ConstantBackoffConfig(10000)),
      ("  kind: jittered\n  maxMs: 10000", JitteredBackoffConfig(None, Some(10000))),
      ("  kind: jittered\n  minMs: 10000", JitteredBackoffConfig(Some(10000), None)),
      (
        """|  kind: jittered
          |  minMs: 300
          |  maxMs: 400""".stripMargin,
        JitteredBackoffConfig(Some(300), Some(400))
      )
    )

  test("configs parse to the correct kinds") {
    // TODO: this could look nicer as a table-based property check
    parse("kind: io.l5d.consecutiveFailures") shouldBe a[ConsecutiveFailuresConfig]
    parse("kind: io.l5d.successRate") shouldBe a[SuccessRateConfig]
    parse("kind: io.l5d.successRateWindowed") shouldBe a[SuccessRateWindowedConfig]
    parse("kind: none") shouldBe a[NoneConfig]
  }

  test("unspecified backoffs should parse to None") {
    forAll(kinds) { parse(_).backoff shouldBe None }
  }

  test("configs with backoff configurations have the correct backoff") {
    for {
      kind <- kinds
      (config, backoff) <- backoffs
    } yield outcomeOf {
      val yaml =
        s"""$kind
             |backoff:
             |$config
             """.stripMargin
      parse(yaml).backoff.value shouldEqual backoff
    }
  }

}
