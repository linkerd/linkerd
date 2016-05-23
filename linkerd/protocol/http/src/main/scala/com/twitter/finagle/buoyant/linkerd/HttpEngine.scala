package com.twitter.finagle.buoyant.linkerd

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonTypeInfo, JsonSubTypes}
import com.fasterxml.jackson.core.{io => _}
import com.twitter.finagle.Http
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
  def mk: Http.param.HttpImpl
}

object HttpEngine {
  class Netty3 extends HttpEngine {
    def mk = Http.param.Netty3Impl
  }

  case class Netty4(http2: Option[Boolean]) extends HttpEngine {
    def mk =
      if (http2.getOrElse(false)) Http2
      else Netty4Impl
  }
}
