package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.HeaderIdentifier

class HeaderIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[HeaderIdentifierConfig]
  override val configId = HeaderIdentifierConfig.kind
}

object HeaderIdentifierInitializer extends HeaderIdentifierInitializer

object HeaderIdentifierConfig {
  val kind = "io.l5d.header"
}

class HeaderIdentifierConfig extends HttpIdentifierConfig {
  var header: Option[String] = None

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = HeaderIdentifier(prefix, header.getOrElse(Headers.Dst.Bound), baseDtab)
}
