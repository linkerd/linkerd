package io.buoyant.namer.rancher

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack, param}
import com.twitter.util.Timer
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class RancherInitializer extends NamerInitializer {
  val configClass = classOf[RancherConfig]
  override def configId = "io.l5d.rancher"
}

object RancherInitializer extends RancherInitializer

case class RancherConfig(
  portMappings: Option[Map[String, Int]],
  maxWait: Option[Int]
) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.rancher")

  @JsonIgnore
  override def experimentalRequired = true

  /**
   * Construct a namer.
   */
  @JsonIgnore
  override def newNamer(params: Stack.Params): RancherNamer = {
    val timer: Timer = params[param.Timer].timer
    val stats = params[param.Stats].statsReceiver.scope(prefix.show.stripPrefix("/"))
    new RancherNamer(prefix, portMappings, maxWait.getOrElse(30), params, stats)(timer)
  }
}
