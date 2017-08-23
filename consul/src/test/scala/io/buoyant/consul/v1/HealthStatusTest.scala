package io.buoyant.consul.v1

import io.buoyant.config.Parser
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HealthStatusTest extends FunSuite with Awaits {

  test("health statuses can be deserialized from lowercase names") {
    val yaml = "[passing, warning, critical, maintenance, any]"
    val mapper = Parser.objectMapper(yaml, Iterable.empty)
    val modes = mapper.readValue[Seq[HealthStatus.Value]](yaml)
    assert(modes == Seq(HealthStatus.Passing, HealthStatus.Warning, HealthStatus.Critical, HealthStatus.Maintenance, HealthStatus.Any))
  }

  test("health statuses can be aggregated by worst case") {
    val statuses = Seq(HealthStatus.Passing, HealthStatus.Warning, HealthStatus.Critical)
    assert(statuses.reduce(HealthStatus.worstCase) == HealthStatus.Critical)
  }

}
