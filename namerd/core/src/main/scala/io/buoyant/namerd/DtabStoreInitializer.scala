package io.buoyant.namerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import io.buoyant.config.{PolymorphicConfig, ConfigInitializer}

abstract class DtabStoreInitializer extends ConfigInitializer

abstract class DtabStoreConfig extends PolymorphicConfig {
  /** This property must be set to true in order to use this dtab store if it is experimental */
  @JsonProperty("experimental")
  var _experimentalEnenabled: Option[Boolean] = None

  /**
   * Indicates whether this is an experimental dtab store.  Experimental dtab stores must have the
   * `experimental` property set to true to be used
   */
  @JsonIgnore
  def experimentalRequired: Boolean = false

  /** If this dtab store is experimental but has not set the `experimental` property. */
  @JsonIgnore
  def disabled = experimentalRequired && !_experimentalEnenabled.contains(true)

  @JsonIgnore
  def mkDtabStore: DtabStore
}
