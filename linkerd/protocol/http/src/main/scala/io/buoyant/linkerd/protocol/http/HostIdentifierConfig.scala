package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.HostIdentifier

class HostIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[HostIdentifierConfig]
  override val configId = HostIdentifierConfig.kind
}

object HostIdentifierInitializer extends HostIdentifierInitializer

object HostIdentifierConfig {
  val kind = "io.l5d.host"
}

class HostIdentifierConfig extends HttpIdentifierConfig {

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = HostIdentifier(prefix, baseDtab)
}
