package io.buoyant.namerd.iface

import io.buoyant.config.Parser
import org.scalatest.FunSuite

class ThriftInterpreterInterfaceConfigTest extends FunSuite {

  test("cache capacity") {
    val yaml = """
      |kind: io.l5d.thriftNameInterpreter
      |cache:
      |  bindingCacheActive: 5000
      |  bindingCacheInactive: 1000
      |  addrCacheActive: 6000
    """.stripMargin

    val config = Parser
      .objectMapper(
        yaml,
        Iterable(Seq(new ThriftInterpreterInterfaceInitializer))
      ).readValue[ThriftInterpreterInterfaceConfig](yaml)

    val capacity = config.cache.get.capacity
    assert(capacity.bindingCacheActive == 5000)
    assert(capacity.bindingCacheInactive == 1000)
    assert(capacity.addrCacheActive == 6000)
    assert(capacity.addrCacheInactive == ThriftNamerInterface.Capacity.default.addrCacheInactive)
  }
}
