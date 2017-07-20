package io.buoyant.linkerd.failureAccrual

import io.buoyant.config.Parser
import io.buoyant.linkerd.{ConstantBackoffConfig, FailureAccrualConfig, JitteredBackoffConfig}
import io.buoyant.test.FunSuite
import org.scalatest.OptionValues
import com.twitter.conversions.time._
import org.scalatest.prop.PropertyChecks
import org.scalacheck.Gen

class ConfigTest extends FunSuite
  with OptionValues
  with PropertyChecks {

  def parse(yaml: String): FailureAccrualConfig = {
    val mapper = Parser.objectMapper(yaml, Seq(Seq(
      new ConsecutiveFailuresInitializer,
      new NoneInitializer,
      new SuccessRateInitializer,
      new SuccessRateWindowedInitializer
    )))
    mapper.readValue[FailureAccrualConfig](yaml)
  }

  val kinds = Seq(
    "kind: io.l5d.consecutiveFailures",
    "kind: io.l5d.successRate",
    "kind: io.l5d.successRateWindowed",
    "kind: none"
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
    assert(parse("kind: io.l5d.consecutiveFailures").isInstanceOf[ConsecutiveFailuresConfig])
    assert(parse("kind: io.l5d.successRate").isInstanceOf[SuccessRateConfig])
    assert(parse("kind: io.l5d.successRateWindowed").isInstanceOf[SuccessRateWindowedConfig])
    assert(parse("kind: none").isInstanceOf[NoneConfig])
  }

  test("unspecified backoffs should parse to None") {
    kinds.foreach { cfg => assert(parse(cfg).backoff.isEmpty) }
  }

  test("configs with backoff configurations have the correct backoff") {
    for {
      kind <- kinds
      (config, backoff) <- backoffs
    } {
      val yaml =
        s"""$kind
             |backoff:
             |$config
             """.stripMargin
      assert(parse(yaml).backoff.value == backoff)
    }
  }

  private[this] val positiveInts = Gen.choose(1, Integer.MAX_VALUE)

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
        val n_unique = JitteredBackoffConfig(Some(min), Some(max)).mk
          .take(300).toSet.size
        assert(n_unique >= 2)
      }
    }
  }

  test("jittered backoff configs throw exceptions when passed invalid min/max") {
    forAll { (min: Int, max: Int) =>
      whenever(min > max || min <= 0 || max <= 0) {
        assertThrows[IllegalArgumentException] { JitteredBackoffConfig(Some(min), Some(max)).mk }
      }
    }
    assertThrows[IllegalArgumentException] { JitteredBackoffConfig(None, Some(1000)).mk }
    assertThrows[IllegalArgumentException] { JitteredBackoffConfig(Some(1000), None).mk }
    assertThrows[IllegalArgumentException] { JitteredBackoffConfig(None, None).mk }
  }

}
