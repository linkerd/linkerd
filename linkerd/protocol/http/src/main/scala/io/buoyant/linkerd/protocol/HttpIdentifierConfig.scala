package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.router.RoutingFactory.Identifier

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
trait HttpIdentifierConfig {
  @JsonIgnore
  def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request]
}
