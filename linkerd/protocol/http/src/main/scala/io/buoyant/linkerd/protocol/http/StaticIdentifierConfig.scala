package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.Identifier
import io.buoyant.router.http.StaticIdentifier

class StaticIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[StaticIdentifierConfig]
  override val configId = "io.l5d.static"
}

object StaticIdentifierInitializer extends StaticIdentifierInitializer

case class StaticIdentifierConfig(path: Path) extends HttpIdentifierConfig {
  assert(path != null, "path is required")

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = new StaticIdentifier(prefix ++ path, baseDtab)
}
