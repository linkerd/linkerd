package io.buoyant.consul.v1

import io.buoyant.config.Parser
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HealthStatusTest extends FunSuite with Awaits {

  test("health statuses can be deserialized from lowercase names") {
    val yaml = "[passing, warning, critical, maintenance]"
    val mapper = Parser.objectMapper(yaml, Iterable.empty)
    val modes = mapper.readValue[Seq[HealthStatus.Value]](yaml)
    assert(modes == Seq(HealthStatus.Passing, HealthStatus.Warning, HealthStatus.Critical, HealthStatus.Maintenance))
  }

  test("health statuses can be compared by worst case") {
    // HealthStatus order should be maintenance > critical > warning > passing
    assert(HealthStatus.worstCase(HealthStatus.Maintenance, HealthStatus.Critical) == HealthStatus.Maintenance)
    assert(HealthStatus.worstCase(HealthStatus.Critical, HealthStatus.Warning) == HealthStatus.Critical)
    assert(HealthStatus.worstCase(HealthStatus.Warning, HealthStatus.Passing) == HealthStatus.Warning)

    val statuses = Seq(HealthStatus.Passing, HealthStatus.Warning, HealthStatus.Critical, HealthStatus.Maintenance)
    assert(statuses.reduce(HealthStatus.worstCase) == HealthStatus.Maintenance)
  }

}
