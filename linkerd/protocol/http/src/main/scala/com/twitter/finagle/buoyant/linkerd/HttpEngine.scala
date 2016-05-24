package com.twitter.finagle.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo, JsonSubTypes}
import com.fasterxml.jackson.core.{io => _}
import com.twitter.finagle.{Http, Stack}
import com.twitter.finagle.http2.Http2
import com.twitter.finagle.netty4.http.exp.Netty4Impl

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "kind"
)
@JsonSubTypes(Array(
  new JsonSubTypes.Type(value = classOf[HttpEngine.Netty3], name = "netty3"),
  new JsonSubTypes.Type(value = classOf[HttpEngine.Netty4], name = "netty4")
))
abstract trait HttpEngine {

  @JsonIgnore
  def mk(params: Stack.Params): Stack.Params
}

object HttpEngine {
  class Netty3 extends HttpEngine {
    def mk(params: Stack.Params) = params + Http.param.Netty3Impl
  }

  case class Netty4(http2: Option[Boolean]) extends HttpEngine {
    def mk(params: Stack.Params) =
      if (http2.getOrElse(false)) {
        params + Http2 + Http.param.Streaming(false)
      } else params + Netty4Impl
  }
}
