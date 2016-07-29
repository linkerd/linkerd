package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.MethodAndServiceIdentifier

class MethodAndServiceIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[MethodAndServiceIdentifierConfig]
  override val configId = MethodAndServiceIdentifierConfig.kind
}

object MethodAndServiceIdentifierInitializer extends MethodAndServiceIdentifierInitializer

object MethodAndServiceIdentifierConfig {
  val kind = "io.l5d.methodAndService"
}

class MethodAndServiceIdentifierConfig extends HttpIdentifierConfig {
  var httpUriInDst: Option[Boolean] = None

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = MethodAndServiceIdentifier(prefix, httpUriInDst.getOrElse(false), baseDtab)
}
