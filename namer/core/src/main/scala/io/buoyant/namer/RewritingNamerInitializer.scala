package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.buoyant.PathMatcher
import com.twitter.finagle.Stack.Params

class RewritingNamerInitializer extends NamerInitializer {
  override val configId = "io.l5d.rewrite"
  val configClass = classOf[RewritingNamerConfig]
}

case class RewritingNamerConfig(
  pattern: String,
  name: String
) extends NamerConfig {
  assert(pattern != null)
  assert(name != null)

  @JsonIgnore
  override def defaultPrefix: Path =
    throw new IllegalArgumentException("The 'prefix' property is required for the io.l5d.rewrite namer.")

  @JsonIgnore override protected def newNamer(params: Params): Namer = new RewritingNamer(PathMatcher(pattern), name)
}
