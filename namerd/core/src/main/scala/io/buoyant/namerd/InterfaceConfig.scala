package io.buoyant.namerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Namer, Path}
import io.buoyant.config.ConfigInitializer
import io.buoyant.config.types.Port
import java.net.{InetAddress, InetSocketAddress}

/**
 * Configures a network interface to namerd functionality.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait InterfaceConfig {
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
