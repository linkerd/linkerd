package io.buoyant.namer.curator

import io.buoyant.namer.NamerInitializer

class CuratorInitializer extends NamerInitializer {
  override def configId: String = "io.l5d.curator"

  override def configClass: Class[_] = {
    classOf[CuratorConfig]
  }
}
