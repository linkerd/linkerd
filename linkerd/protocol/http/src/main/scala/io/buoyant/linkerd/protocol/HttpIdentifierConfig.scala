package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.router.RoutingFactory.Identifier

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
trait HttpIdentifierConfig {

  @JsonProperty("kind")
  var kind: String = ""

  @JsonIgnore
  def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request]
}
