package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.{Path, Namer => FinagleNamer, Stack}
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.linkerd.config.ConfigInitializer

sealed trait NamingFactory

object NamingFactory {
  case class Interpreter(kind: String, mk: () => NameInterpreter) extends NamingFactory
  case class Namer(kind: String, prefix: Path, mk: () => FinagleNamer) extends NamingFactory
}

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
trait NamingFactoryConfig {

  def kind = getClass.getCanonicalName

  /**
   * Construct a NamingFactory.
   */
  @JsonIgnore
  def newFactory(params: Stack.Params): NamingFactory
}

trait NamerConfig extends NamingFactoryConfig {
  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  @JsonIgnore
  def defaultPrefix: Path

  protected[this] def prefix = _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  def newNamer(params: Stack.Params): FinagleNamer

  @JsonIgnore
  def newFactory(params: Stack.Params): NamingFactory = {
    val namer = newNamer(params)
    NamingFactory.Namer(kind, prefix, () => namer)
  }
}

trait NamerInitializer extends ConfigInitializer
