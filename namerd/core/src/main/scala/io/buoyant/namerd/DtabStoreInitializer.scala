package io.buoyant.namerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import io.buoyant.config.ConfigInitializer
import io.buoyant.config.types.Port
import java.net.{InetAddress, InetSocketAddress}

trait DtabStoreInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait DtabStoreConfig {

  var ip: Option[InetAddress] = None
  var port: Option[Port] = None

  @JsonIgnore
  def mkDtabStore: DtabStore

  @JsonIgnore
  def addr = new InetSocketAddress(
    ip.getOrElse(InetAddress.getLoopbackAddress),
    port.map(_.port).getOrElse(4180)
  )
}
