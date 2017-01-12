package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.config.Config
import io.buoyant.router.RoutingFactory.Identifier

trait HttpIdentifierConfig extends Config {
  @JsonIgnore
  def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request]
}
