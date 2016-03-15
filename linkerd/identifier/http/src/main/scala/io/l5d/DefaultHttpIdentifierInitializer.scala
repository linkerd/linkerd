package io.l5d.identifier

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.router.http.DefaultIdentifier
import io.l5d.HttpIdentifierConfig

class DefaultHttpIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[DefaultHttpIdentifierConfig]
  override val configId = DefaultHttpIdentifierConfig.kind
}

object DefaultHttpIdentifierInitializer extends DefaultHttpIdentifierInitializer

object DefaultHttpIdentifierConfig {
  val kind = "default"
}

class DefaultHttpIdentifierConfig extends HttpIdentifierConfig {
  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = DefaultIdentifier(prefix, httpUriInDst.getOrElse(false), baseDtab)
}
