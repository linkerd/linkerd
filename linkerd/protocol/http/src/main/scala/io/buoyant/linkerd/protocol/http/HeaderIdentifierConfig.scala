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
  val defaultHeader = Headers.Prefix + "name"
}

case class HeaderIdentifierConfig(
  header: Option[String] = None
) extends HttpIdentifierConfig {

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = HeaderIdentifier(
    prefix,
    header.getOrElse(HeaderIdentifierConfig.defaultHeader),
    headerPath = true,
    baseDtab
  )
}
