package com.twitter.finagle.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonSubTypes}
import com.fasterxml.jackson.core.{io => _}
import com.twitter.finagle.{Http, Stack}
import io.buoyant.config.PolymorphicConfig

@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[Netty3HttpEngine], name = "netty3"),
  new JsonSubTypes.Type(value = classOf[Netty4HttpEngine], name = "netty4")
))
abstract class HttpEngine extends PolymorphicConfig {
  @JsonIgnore
  def mk(params: Stack.Params): Stack.Params
}

class Netty3HttpEngine extends HttpEngine {
  def mk(params: Stack.Params) = params + Http.Netty3Impl
}

class Netty4HttpEngine extends HttpEngine {
  def mk(params: Stack.Params) = params + Http.Netty4Impl
}
