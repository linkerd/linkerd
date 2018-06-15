package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Fields
import com.twitter.finagle.{Dtab, Path, Stack}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.HeaderIdentifier

class HeaderTokenIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[HeaderTokenIdentifierConfig]
  override val configId = HeaderTokenIdentifierConfig.kind
}

object HeaderTokenIdentifierInitializer extends HeaderTokenIdentifierInitializer

object HeaderTokenIdentifierConfig {
  val kind = "io.l5d.header.token"
  val defaultHeader = Fields.Host
}

case class HeaderTokenIdentifierConfig(
  header: Option[String] = None
) extends HttpIdentifierConfig {

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base,
    routerParams: Stack.Params = Stack.Params.empty
  ) = HeaderIdentifier(
    prefix,
    header.getOrElse(HeaderTokenIdentifierConfig.defaultHeader),
    headerPath = false,
    baseDtab
  )
}
