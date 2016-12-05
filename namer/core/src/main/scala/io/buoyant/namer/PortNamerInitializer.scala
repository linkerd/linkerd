package io.buoyant.namer
import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Namer, Path}
import com.twitter.finagle.Stack.Params

class PortNamerConfig extends NamerConfig {

  @JsonIgnore
  override def defaultPrefix = Path.read("/io.l5d.port")

  @JsonIgnore
  override protected def newNamer(params: Params): Namer = new PortNamer(prefix)
}

class PortNamerInitializer extends NamerInitializer {
  override val configId = "io.l5d.port"
  val configClass = classOf[PortNamerConfig]
}

object PortNamerInitializer extends PortNamerInitializer
