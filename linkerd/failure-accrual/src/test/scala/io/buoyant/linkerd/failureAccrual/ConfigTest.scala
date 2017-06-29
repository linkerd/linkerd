package io.buoyant.linkerd.failureAccrual

import io.buoyant.config.Parser
import io.buoyant.linkerd.{ConstantBackoffConfig, FailureAccrualConfig, JitteredBackoffConfig}
import io.buoyant.test.FunSuite
import org.scalatest.{Matchers, OptionValues, OutcomeOf}
import com.twitter.conversions.time._
import org.scalatest.prop.PropertyChecks
import org.scalacheck.Gen

class ConfigTest extends FunSuite
  with Matchers
  with OptionValues
  with PropertyChecks
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

  private[this] val positiveInts = for { n <- Gen.choose(1, Integer.MAX_VALUE) } yield n

  test("constant backoff configs produce streams of constant durations") {
    forAll { (n: Int) =>
      ConstantBackoffConfig(n).mk.take(100).foreach(d => assert(d == n.millis))
    }
  }

  test("jittered backoff configs produce streams of durations between the minimum and maximum") {
    forAll((positiveInts, "min"), (positiveInts, "max")) { (min: Int, max: Int) =>
      whenever(min < max) {
        JitteredBackoffConfig(Some(min), Some(max)).mk
          .take(100)
          .foreach(d => assert(d >= min.millis && d <= max.millis))
      }
    }
  }

  test("jittered backoff configs produce streams containing at least two unique durations") {
    forAll((positiveInts, "min"), (positiveInts, "max")) { (min: Int, max: Int) =>
      whenever(min < max) {
        JitteredBackoffConfig(Some(min), Some(max)).mk.take(300).toSet.size should be >= 2
      }
    }
  }

  test("jittered backoff configs throw exceptions when passed invalid min/max") {
    forAll { (min: Int, max: Int) =>
      whenever(min >= max || min <= 0 || max <= 0) {
        an[IllegalArgumentException] should be thrownBy JitteredBackoffConfig(Some(min), Some(max)).mk
      }
    }
    an[IllegalArgumentException] should be thrownBy JitteredBackoffConfig(None, Some(1000)).mk
    an[IllegalArgumentException] should be thrownBy JitteredBackoffConfig(Some(1000), None).mk
    an[IllegalArgumentException] should be thrownBy JitteredBackoffConfig(None, None).mk
  }

}
