package io.buoyant.namer

import com.twitter.finagle.Path

class booUrnsNamer extends TestNamerConfig {
  override def defaultPrefix = Path.read("/boo/urns")
}
