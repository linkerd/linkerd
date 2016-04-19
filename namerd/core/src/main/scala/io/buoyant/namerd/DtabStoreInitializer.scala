package io.buoyant.namerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import io.buoyant.config.ConfigInitializer
import io.buoyant.config.types.Port
import java.net.{InetAddress, InetSocketAddress}

trait DtabStoreInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait DtabStoreConfig {
  @JsonIgnore
  def mkDtabStore: DtabStore
}
