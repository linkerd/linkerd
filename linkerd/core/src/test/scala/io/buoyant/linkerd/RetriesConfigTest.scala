package io.buoyant.linkerd

import com.twitter.finagle.Backoff
import com.twitter.util.Duration
import io.buoyant.config.Parser
import io.buoyant.namer.{ConstantBackoffConfig, JitteredBackoffConfig}
import io.buoyant.router.RetryBudgetConfig
import org.scalatest.FunSuite

class RetriesConfigTest extends FunSuite {

  def parse(yaml: String): RetriesConfig = {
    val mapper = Parser.objectMapper(yaml, Nil)
    mapper.readValue[RetriesConfig](yaml)
  }

  test("empty") {
    assert(parse("{}") == RetriesConfig(None, None))
  }

  test("constant backoff") {
    val yaml =
      s"""|backoff:
          |  kind: constant
          |  ms: 30
          |""".stripMargin
    val config = ConstantBackoffConfig(30)
    assert(parse(yaml) == RetriesConfig(Some(config), None))
    assert(config.mk.isInstanceOf[Backoff])
  }

  test("jittered backoff") {
    val yaml =
      s"""|backoff:
          |  kind: jittered
          |  minMs: 30
          |  maxMs: 6000
          |""".stripMargin
    val config = JitteredBackoffConfig(Some(30), Some(6000))
    assert(parse(yaml) == RetriesConfig(Some(config), None))
    assert(config.mk.isInstanceOf[Backoff])
  }

  test("jittered backoff: no min") {
    val yaml =
      s"""|backoff:
          |  kind: jittered
          |  maxMs: 6000
          |""".stripMargin
    val config = JitteredBackoffConfig(None, Some(6000))
    assert(parse(yaml) == RetriesConfig(Some(config), None))
    val e = intercept[IllegalArgumentException] {
      assert(config.mk.isInstanceOf[Backoff])
    }
    assert(e.getMessage == "'minMs' must be specified")
  }

  test("budget") {
    val yaml =
      s"""|budget:
          |  ttlSecs: 12
          |  minRetriesPerSec: 20
          |  percentCanRetry: 0.33
          |""".stripMargin
    assert(parse(yaml) ==
      RetriesConfig(None, Some(RetryBudgetConfig(Some(12), Some(20), Some(0.33)))))
  }

}
