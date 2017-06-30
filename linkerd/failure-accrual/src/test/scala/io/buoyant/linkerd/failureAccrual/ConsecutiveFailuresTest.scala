package io.buoyant.linkerd.failureAccrual

import com.twitter.finagle.liveness.FailureAccrualPolicy
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.linkerd.{FailureAccrualConfig, FailureAccrualInitializer}
import io.buoyant.test.FunSuite
import org.scalacheck.Gen
import org.scalatest.Matchers
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class ConsecutiveFailuresTest extends FunSuite
  with Matchers
  with GeneratorDrivenPropertyChecks {
  def parse(yaml: String): FailureAccrualConfig = {
    val mapper = Parser.objectMapper(yaml, Seq(Seq(
      new ConsecutiveFailuresInitializer,
      new NoneInitializer,
      new SuccessRateInitializer,
      new SuccessRateWindowedInitializer
    )))
    mapper.readValue[FailureAccrualConfig](yaml)
  }

  test("sanity") {
    val failures = 2
    val config = ConsecutiveFailuresConfig(failures)
    assert(config.policy().isInstanceOf[FailureAccrualPolicy])
    assert(config.failures == failures)
  }

  test("service registration") {
    assert(LoadService[FailureAccrualInitializer].exists(_.isInstanceOf[ConsecutiveFailuresInitializer]))
  }

  test("constant backoff") {
    val yaml = """|kind: io.l5d.consecutiveFailures
                  |failures: 5
                  |backoff:
                  |  kind: constant
                  |  ms: 10000""".stripMargin
    val policy = parse(yaml).policy()
    val probeDelays = Stream.continually(policy.markDeadOnFailure()).take(20)
    probeDelays.take(4) should contain only None
    probeDelays.drop(4).foreach(delay => assert(delay.isDefined))
  }

  test("default (exponential) backoff") {
    val yaml =
      """|kind: io.l5d.consecutiveFailures
         |failures: 5""".stripMargin

    val policy = parse(yaml).policy()
    val probeDelays = Stream.continually(policy.markDeadOnFailure()).take(20)
    probeDelays shouldBe sorted // todo: better assertion that the increase is exponential
    probeDelays.take(4) should contain only None
    probeDelays.drop(4).foreach(delay => assert(delay.isDefined))
  }

  val smallInts = Gen.choose(1, 100).suchThat(n => n > 1 && n < 100)

  test("arbitrary number of consecutive failures") {

    forAll((smallInts, "number of failures")) { n =>
      val yaml =
        s"""|kind: io.l5d.consecutiveFailures
            |failures: $n""".stripMargin

      val policy = parse(yaml).policy()
      val probeDelays = Stream.continually(policy.markDeadOnFailure()).take(n + 20)
      probeDelays shouldBe sorted
      probeDelays.take(n - 1) should contain only None
      probeDelays.drop(n).foreach(delay => assert(delay.isDefined))

    }
  }

}
