package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.{Path, Namer, Stack}
import io.buoyant.config.ConfigInitializer

/**
 * Read a single namer configuration in the form:
 *
 * <pre>
 *   kind: io.l5d.izzle
 *   prefix: /i
 *   frizzle: dee
 *   swizzle: dah
 * </pre>
 *
 * In this example _io.l5d.izzle_ must be the _kind_ of a
 * [[NamerInitializer]] in `namers`.  _frizzle_ and _swizzle_ are
 * namer-specific options.  This namer refines names beginning with
 * `/i` (after this prefix has been stripped).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonAutoDetect(fieldVisibility = Visibility.PUBLIC_ONLY)
trait NamerConfig {
  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def defaultPrefix: Path

  @JsonIgnore
  def prefix = NamerConfig.hash ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def newNamer(params: Stack.Params): Namer
}

object NamerConfig {
  val hash = Path.Utf8("#")
}

trait NamerInitializer extends ConfigInitializer
