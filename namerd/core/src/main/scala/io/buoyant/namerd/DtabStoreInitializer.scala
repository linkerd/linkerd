package io.buoyant.namerd

import com.fasterxml.jackson.annotation.{JsonProperty, JsonIgnore, JsonTypeInfo}
import io.buoyant.config.ConfigInitializer
import io.buoyant.config.types.Port
import java.net.{InetAddress, InetSocketAddress}

trait DtabStoreInitializer extends ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait DtabStoreConfig {
  @JsonProperty("experimental")
  var _experimentalEnenabled: Option[Boolean] = None

  @JsonIgnore
  def experimental: Boolean = false

  @JsonIgnore
  def disabled = experimental && !_experimentalEnenabled.contains(true)

  @JsonIgnore
  def mkDtabStore: DtabStore
}
