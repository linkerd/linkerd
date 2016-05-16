package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.DefaultIdentifier

class DefaultIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[DefaultIdentifierConfig]
  override val configId = DefaultIdentifierConfig.kind
}

object DefaultIdentifierInitializer extends DefaultIdentifierInitializer

object DefaultIdentifierConfig {
  val kind = "default"
}

class DefaultIdentifierConfig extends HttpIdentifierConfig {
  var httpUriInDst: Option[Boolean] = None

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = DefaultIdentifier(prefix, httpUriInDst.getOrElse(false), baseDtab)
}
