package io.buoyant.namer
import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params

class LocalhostNamerConfig extends NamerConfig {

  @JsonIgnore
  override def defaultPrefix = Path.read("/io.l5d.localhost")

  @JsonIgnore
  override protected def newNamer(params: Params): Namer = new LocalhostNamer(prefix)
}

class LocalhostNamerInitializer extends NamerInitializer {
  override val configId = "io.l5d.localhost"
  val configClass = classOf[LocalhostNamerConfig]
}

object LocalhostNamerInitializer extends LocalhostNamerInitializer
