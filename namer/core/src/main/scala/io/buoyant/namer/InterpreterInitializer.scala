package io.buoyant.namer

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}
import com.twitter.finagle.{param, Stack}
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.config.{ConfigInitializer, PolymorphicConfig}
import scala.util.control.NoStackTrace

abstract class InterpreterConfig extends PolymorphicConfig {

  /** This property must be set to true in order to use this interpreter if it is experimental */
  @JsonProperty("experimental")
  var _experimentalEnabled: Option[Boolean] = None

  var transformers: Option[Seq[TransformerConfig]] = None

  /**
   * Indicates whether this is an experimental interprester.  Experimental interpresters must have
   * the `experimental` property set to true to be used
   */
  @JsonIgnore
  def experimentalRequired = false

  /** If this interpreter is experimental but has not set the `experimental` property. */
  @JsonIgnore
  def disabled = experimentalRequired && !_experimentalEnabled.contains(true)

  @JsonIgnore
  protected def newInterpreter(params: Stack.Params): NameInterpreter

  @JsonIgnore
  final def interpreter(params: Stack.Params): NameInterpreter = {
    if (disabled) {
      val msg = s"The ${getClass.getCanonicalName} interpreter is experimental and must be " +
        "explicitly enabled by setting the `experimental' parameter to `true'."
      throw new IllegalArgumentException(msg) with NoStackTrace
    }

    val transformerStats = params[param.Stats].statsReceiver.scope("transformer")

    transformers.toSeq.flatten.foldLeft(newInterpreter(params)) { (ni, transformerConfig) =>
      val stats = transformerStats.scope(transformerConfig.prefix.show.stripPrefix("/"))
      val transformer = transformerConfig.mk(Stack.Params.empty + param.Stats(stats))
      (ni, transformer) match {
        case (delegator: Delegator, transformer: DelegatingNameTreeTransformer) =>
          transformer.delegatingWrap(delegator)
        case (_, transformer) => transformer.wrap(ni)
      }
    }
  }
}

trait NamespacedInterpreterConfig extends InterpreterConfig {
  def namespace: Option[String]
}

abstract class InterpreterInitializer extends ConfigInitializer
