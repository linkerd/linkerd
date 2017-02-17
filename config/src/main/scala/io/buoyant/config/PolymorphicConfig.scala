package io.buoyant.config

import com.fasterxml.jackson.annotation.{JsonProperty, JsonTypeInfo}

/**
 * An abstract class that defines the property "kind" as both a var on
 * the object and as type information for JSON de/serialization.
 *
 * Config objects that expect to be subclassed by multiple other configs
 *   with different "kind" values should extend this trait.
 */
@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY,
  property = "kind",
  visible = true
)
abstract class PolymorphicConfig {
  @JsonProperty("kind")
  var kind: String = ""
}
