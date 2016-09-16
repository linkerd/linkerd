package io.buoyant.consul.v1

import io.buoyant.config.Parser
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ConsistencyModeTest extends FunSuite with Awaits {

  test("consistency nodes can be deserialized from lowercase names") {
    val yaml = "[default, consistent, stale]"
    val mapper = Parser.objectMapper(yaml, Iterable.empty)
    val modes = mapper.readValue[Seq[ConsistencyMode]](yaml)
    assert(modes == Seq(ConsistencyMode.Default, ConsistencyMode.Consistent, ConsistencyMode.Stale))
  }

}
