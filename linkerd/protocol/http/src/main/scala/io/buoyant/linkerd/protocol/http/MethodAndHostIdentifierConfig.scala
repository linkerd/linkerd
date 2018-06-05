package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path, Stack}
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
    baseDtab: () => Dtab = () => Dtab.base,
    routerParams: Stack.Params = Stack.Params.empty
  ) = MethodAndHostIdentifier(prefix, httpUriInDst.getOrElse(false), baseDtab)
}
