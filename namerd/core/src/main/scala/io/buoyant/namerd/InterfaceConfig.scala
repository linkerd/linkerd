package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Namer, Path}
import io.buoyant.config.types.Port
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}
import java.net.{InetAddress, InetSocketAddress}

/**
 * Configures a network interface to namerd functionality.
 */
abstract class InterfaceConfig extends PolymorphicConfig {
  var ip: Option[InetAddress] = None
  var port: Option[Port] = None

  @JsonIgnore
  def addr = new InetSocketAddress(
    ip.getOrElse(defaultAddr.getAddress),
    port.map(_.port).getOrElse(defaultAddr.getPort)
  )

  @JsonIgnore
  protected def defaultAddr: InetSocketAddress

  def mk(store: DtabStore, namers: Map[Path, Namer], stats: StatsReceiver): Servable
}

abstract class InterfaceInitializer extends ConfigInitializer
