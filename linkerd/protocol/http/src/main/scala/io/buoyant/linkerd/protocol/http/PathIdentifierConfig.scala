package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Dtab, Path}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.http.PathIdentifier

class PathIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[PathIdentifierConfig]
  override val configId = PathIdentifierConfig.kind
}

object PathIdentifierInitializer extends PathIdentifierInitializer

object PathIdentifierConfig {
  val kind = "io.l5d.path"
}

class PathIdentifierConfig extends HttpIdentifierConfig {
  var segments: Option[Int] = None

  @JsonIgnore
  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ) = PathIdentifier(prefix, segments.getOrElse(1), baseDtab)
}
