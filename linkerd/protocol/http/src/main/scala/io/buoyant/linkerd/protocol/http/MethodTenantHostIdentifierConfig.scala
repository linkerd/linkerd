package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.MethodTenantHostIdentifier

class MethodTenantHostIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[MethodTenantHostIdentifierConfig]
  override val configId = MethodTenantHostIdentifierConfig.kind
}

object MethodTenantHostIdentifierInitializer extends MethodTenantHostIdentifierInitializer

object MethodTenantHostIdentifierConfig {
  val kind = "com.medallia.methodTenantHost"
}

class MethodTenantHostIdentifierConfig extends HttpIdentifierConfig {

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = MethodTenantHostIdentifier.mk(prefix, baseDtab)
}
