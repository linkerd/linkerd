package io.buoyant.namer.serversets

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Stack, Path}
import io.buoyant.config.types.{HostAndPort, Port}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

class ServersetsInitializer extends NamerInitializer {
  val configClass = classOf[ServersetsConfig]
  override def configId = "io.l5d.serversets"
}

object ServersetsInitializer extends ServersetsInitializer

case class ServersetsConfig(zkAddrs: Seq[HostAndPort]) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.serversets")

  @JsonIgnore
  val connectString = zkAddrs.map(_.toString(defaultPort = Port(2181))).mkString(",")

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = new ServersetNamer(connectString, prefix)
}
