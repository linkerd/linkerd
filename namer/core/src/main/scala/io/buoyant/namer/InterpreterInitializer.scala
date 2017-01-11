package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonProperty, JsonTypeInfo}
import com.twitter.finagle.Stack
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.config.ConfigInitializer
import scala.annotation.meta.getter
import scala.util.control.NoStackTrace

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
trait InterpreterConfig {

  /** This property must be set to true in order to use this interpreter if it is experimental */
  @JsonProperty("experimental")
  var _experimentalEnabled: Option[Boolean] = None

  var transformers: Option[Seq[TransformerConfig]] = None

  @JsonProperty("kind")
  var kind: String = ""

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
    transformers.toSeq.flatten.foldLeft(newInterpreter(params)) { (ni, transformerConfig) =>
      (ni, transformerConfig.mk) match {
        case (delegator: Delegator, transformer: DelegatingNameTreeTransformer) =>
          transformer.delegatingWrap(delegator)
        case (_, transformer) => transformer.wrap(ni)
      }
    }
  }
}

abstract class InterpreterInitializer extends ConfigInitializer
