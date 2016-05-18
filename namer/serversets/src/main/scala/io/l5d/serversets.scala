package io.l5d

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Stack, Path}
import io.buoyant.config.types.{HostAndPort, Port}
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.namer.serversets.ServersetNamer

class ServersetsInitializer extends NamerInitializer {
  val configClass = classOf[serversets]
}

object ServersetsInitializer extends ServersetsInitializer

case class serversets(zkAddrs: Seq[HostAndPort]) extends NamerConfig {
  @JsonIgnore
  override def defaultPrefix: Path = Path.read("/io.l5d.serversets")

  @JsonIgnore
  val connectString = zkAddrs.map(_.toString(defaultPort = Port(2181))).mkString(",")

  /**
   * Construct a namer.
   */
  def newNamer(params: Stack.Params) = new ServersetNamer(connectString, prefix)
}
