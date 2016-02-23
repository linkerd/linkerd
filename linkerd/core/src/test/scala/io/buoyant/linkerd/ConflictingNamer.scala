package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import io.buoyant.linkerd.config.Parser

class ConflictingNamer extends NamerInitializer {
  val configClass = classOf[ConflictingNamerConfig]
  val configId = "io.buoyant.linkerd.TestNamer"
}

object ConflictingNamer extends ConflictingNamer

class ConflictingNamerConfig extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = ???
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = ???
}
