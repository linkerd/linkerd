package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import io.buoyant.linkerd.config.Parser

class ConflictingNamerInitializer extends NamerInitializer {
  val configClass = classOf[ConflictingNamer]
  override val configId = "io.buoyant.linkerd.TestNamer"
}

object ConflictingNamerInitializer extends ConflictingNamerInitializer

class ConflictingNamer extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = ???
  @JsonIgnore
  override def newNamer(params: Stack.Params): Namer = ???
}
