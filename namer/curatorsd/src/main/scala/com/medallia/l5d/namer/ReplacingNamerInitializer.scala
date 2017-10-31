package com.medallia.l5d.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.{Namer, Path}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class ReplacingNamerInitializer extends NamerInitializer {
  override val configId = "com.medallia.l5d.replacing"
  val configClass = classOf[ReplacingNamerConfig]
}

case class ReplacingNamerConfig(
  pattern: String,
  replace: String
) extends NamerConfig {
  assert(pattern != null)
  assert(replace != null)

  @JsonIgnore
  override def defaultPrefix: Path =
    throw new IllegalArgumentException("The 'prefix' property is required for the com.medallia.l5d.replacing namer.")

  @JsonIgnore
  override protected def newNamer(params: Params): Namer = new ReplacingNamer(pattern.r, replace)
}
