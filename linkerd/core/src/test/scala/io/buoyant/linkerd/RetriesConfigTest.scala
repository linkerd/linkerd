package io.buoyant.linkerd

import com.twitter.finagle.service.Retries
import io.buoyant.config.Parser
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
    assert(parse(yaml) == RetriesConfig(Some(ConstantBackoffConfig(30)), None))
  }

  test("jittered backoff") {
    val yaml =
      s"""|backoff:
          |  kind: jittered
          |  minMs: 30
          |  maxMs: 6000
          |""".stripMargin
    assert(parse(yaml) == RetriesConfig(Some(JitteredBackoffConfig(30, 6000)), None))
  }

  test("jittered backoff: no min") {
    val yaml =
      s"""|backoff:
          |  kind: jittered
          |  maxMs: 6000
          |""".stripMargin
    assert(parse(yaml) == RetriesConfig(Some(JitteredBackoffConfig(0, 6000)), None))
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
