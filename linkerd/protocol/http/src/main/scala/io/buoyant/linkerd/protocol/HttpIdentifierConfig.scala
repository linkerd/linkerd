package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.{JsonProperty, JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.router.RoutingFactory.Identifier
import scala.annotation.meta.getter

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
