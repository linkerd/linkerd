package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.MethodAndHostIdentifier

class MethodAndHostIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[MethodAndHostIdentifierConfig]
  override val configId = MethodAndHostIdentifierConfig.kind
}

object MethodAndHostIdentifierInitializer extends MethodAndHostIdentifierInitializer

object MethodAndHostIdentifierConfig {
  val kind = "io.l5d.methodAndHost"
}

class MethodAndHostIdentifierConfig extends HttpIdentifierConfig {
  var httpUriInDst: Option[Boolean] = None

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = MethodAndHostIdentifier(prefix, httpUriInDst.getOrElse(false), baseDtab)
}
