package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.EnvTenantHostIdentifier

class EnvTenantHostIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[EnvTenantHostIdentifierConfig]
  override val configId = EnvTenantHostIdentifierConfig.kind
}

object EnvTenantHostIdentifierInitializer extends EnvTenantHostIdentifierInitializer

object EnvTenantHostIdentifierConfig {
  val kind = "com.medallia.envTenantHost"
}

class EnvTenantHostIdentifierConfig extends HttpIdentifierConfig {

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = EnvTenantHostIdentifier.mk(prefix, baseDtab)
}
