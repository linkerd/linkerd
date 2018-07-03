package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle._
import io.buoyant.config.{ConfigInitializer, PolymorphicConfig}

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
abstract class NamerConfig extends PolymorphicConfig {

  @JsonProperty("prefix")
  var _prefix: Option[Path] = None

  /** This property must be set to true in order to use this namer if it is experimental */
  @JsonProperty("experimental")
  var _experimentalEnabled: Option[Boolean] = None

  var transformers: Option[Seq[TransformerConfig]] = None

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
  def prefix = Paths.ConfiguredNamerPrefix ++ _prefix.getOrElse(defaultPrefix)

  @JsonIgnore
  protected def newNamer(params: Stack.Params): Namer

  @JsonIgnore
  def mk(params: Stack.Params): Namer = {

    val namerStats = params[param.Stats].statsReceiver.scope(prefix.show.stripPrefix("/"))

    val underlying = newNamer(params + param.Stats(namerStats))

    val transformerStats = namerStats.scope("transformer")

    transformers.toSeq.flatten
      .map { config =>
        val stats = param.Stats(transformerStats.scope(config.prefix.show.stripPrefix("/")))
        config.mk(Stack.Params.empty + stats)
      }
      .foldLeft(underlying) { (namer, transformer) =>
        transformer.wrap(namer)
      }
  }
}

abstract class NamerInitializer extends ConfigInitializer
