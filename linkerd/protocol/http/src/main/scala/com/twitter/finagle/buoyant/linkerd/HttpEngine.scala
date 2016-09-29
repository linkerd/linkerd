package com.twitter.finagle.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo, JsonSubTypes}
import com.fasterxml.jackson.core.{io => _}
import com.twitter.finagle.{Http, Stack}
import com.twitter.finagle.http2.Http2
import com.twitter.finagle.http2.param.PriorKnowledge
import com.twitter.finagle.netty4.http.exp.Netty4Impl

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "kind"
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[Netty3HttpEngine], name = "netty3"),
  new JsonSubTypes.Type(value = classOf[Netty4HttpEngine], name = "netty4"),
  new JsonSubTypes.Type(value = classOf[H2Engine], name = "h2")
))
abstract class HttpEngine {
  @JsonIgnore
  def mk(params: Stack.Params): Stack.Params
}

class Netty3HttpEngine extends HttpEngine {
  def mk(params: Stack.Params) = params + Http.param.Netty3Impl
}

class Netty4HttpEngine extends HttpEngine {
  def mk(params: Stack.Params) = params + Netty4Impl
}

case class H2Engine(priorKnowledge: Option[Boolean]) extends HttpEngine {
  def mk(params: Stack.Params) = params + Http2 + PriorKnowledge(priorKnowledge.getOrElse(false))
}
