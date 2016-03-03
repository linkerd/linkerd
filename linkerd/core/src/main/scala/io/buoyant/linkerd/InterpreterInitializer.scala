package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.{JsonAutoDetect, JsonIgnore, JsonTypeInfo}
import com.twitter.finagle.Stack
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.linkerd.config.ConfigInitializer

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonAutoDetect(fieldVisibility = Visibility.PUBLIC_ONLY)
trait InterpreterConfig {
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter
}

trait InterpreterInitializer extends ConfigInitializer
