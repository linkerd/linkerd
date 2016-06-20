package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle._
import com.twitter.util.{Future, Activity}
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
 * `/#/i` (after this prefix has been stripped).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonAutoDetect(fieldVisibility = Visibility.PUBLIC_ONLY)
trait NamerConfig {
  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  /** This property must be set to true in order to use this namer if it is experimental */
  @JsonProperty("experimental")
  var _experimentalEnabled: Option[Boolean] = None

  /**
   * Indicates whether this is an experimental namer.  Experimental namers must have the
   * `experimental` property set to true to be used
   */
  @JsonIgnore
  def experimentalRequired = false

  /** If this namer is experimental but has not set the `experimental` property. */
  @JsonIgnore
  def disabled = experimentalRequired && !_experimentalEnabled.contains(true)

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

abstract class NamerInitializer extends ConfigInitializer
