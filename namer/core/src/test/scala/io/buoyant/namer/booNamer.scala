package io.buoyant.namer

import com.twitter.finagle.Path

class booNamer extends TestNamerConfig {
  override def defaultPrefix = Path.read("/boo")
}
