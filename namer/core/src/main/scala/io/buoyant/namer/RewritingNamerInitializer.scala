package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params
import io.buoyant.namer.util.PathMatcher

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
  override def defaultPrefix: Path = Path.read("/io.l5d.rewrite")

  @JsonIgnore override protected def newNamer(params: Params): Namer = new RewritingNamer(PathMatcher(pattern), name)
}
