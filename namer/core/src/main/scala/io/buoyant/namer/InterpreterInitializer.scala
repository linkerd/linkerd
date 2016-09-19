package io.buoyant.namer

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.Stack
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonAutoDetect(fieldVisibility = Visibility.PUBLIC_ONLY)
trait InterpreterConfig {

  var transformers: Option[Seq[TransformerConfig]] = None

  @JsonIgnore
  protected def newInterpreter(params: Stack.Params): NameInterpreter

  @JsonIgnore
  final def interpreter(params: Stack.Params): NameInterpreter = {
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
