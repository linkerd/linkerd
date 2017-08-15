package io.buoyant.namerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Namer, Path}
import io.buoyant.config.types.Port
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}
import com.twitter.finagle.buoyant.TlsServerConfig
import com.twitter.finagle.netty4.ssl.server.Netty4ServerEngineFactory
import java.net.{InetAddress, InetSocketAddress}

/**
 * Configures a network interface to namerd functionality.
 */
abstract class InterfaceConfig extends PolymorphicConfig {
  var ip: Option[InetAddress] = None
  var port: Option[Port] = None
  var tls: Option[TlsServerConfig] = None

  @JsonIgnore
  def addr = new InetSocketAddress(
    ip.getOrElse(defaultAddr.getAddress),
    port.map(_.port).getOrElse(defaultAddr.getPort)
  )

  @JsonIgnore
  def tlsParams = tls.map(_.params(None, Netty4ServerEngineFactory())).getOrElse(Stack.Params.empty)

  @JsonIgnore
  protected def defaultAddr: InetSocketAddress

  def mk(store: DtabStore, namers: Map[Path, Namer], stats: StatsReceiver): Servable
}

abstract class InterfaceInitializer extends ConfigInitializer
