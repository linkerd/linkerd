package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.CanaryAndServiceIdentifier

class CanaryAndServiceIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[CanaryAndServiceIdentifierConfig]
  override val configId = CanaryAndServiceIdentifierConfig.kind
}

object CanaryAndServiceIdentifierInitializer extends CanaryAndServiceIdentifierInitializer

object CanaryAndServiceIdentifierConfig {
  val kind = "io.l5d.canaryAndService"
}

class CanaryAndServiceIdentifierConfig extends HttpIdentifierConfig {
  var httpUriInDst: Option[Boolean] = None

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = CanaryAndServiceIdentifier(prefix, httpUriInDst.getOrElse(false), baseDtab)
}
