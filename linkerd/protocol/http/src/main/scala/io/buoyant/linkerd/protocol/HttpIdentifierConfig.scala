package io.buoyant.linkerd.protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path, Stack}
import io.buoyant.config.PolymorphicConfig
import io.buoyant.router.RoutingFactory.Identifier

abstract class HttpIdentifierConfig extends PolymorphicConfig {
  @JsonIgnore
  def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base,
    routerParams: Stack.Params = Stack.Params.empty
  ): Identifier[Request]
}
