package io.buoyant.config

import com.fasterxml.jackson.annotation.{JsonProperty, JsonTypeInfo}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
trait Config {
  @JsonProperty("kind")
  var kind: String = ""
}
