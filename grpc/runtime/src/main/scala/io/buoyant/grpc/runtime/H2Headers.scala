package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2

private[runtime] object H2Headers {
  private[this] val AuthorityHeader = h2.Headers.Authority -> ""
  private[this] val ContentTypeHeader = "content-type" -> "application/grpc+proto"
  private[this] val MethodHeader = h2.Headers.Method -> h2.Method.Post.toString
  private[this] val SchemeHeader = h2.Headers.Scheme -> "http"

  def responseHeaders(http2Status: h2.Status = h2.Status.Ok): h2.Headers = h2.Headers(
    h2.Headers.Status -> http2Status.toString,
    ContentTypeHeader
  )

  def requestHeaders(path: String): h2.Headers = h2.Headers(
    h2.Headers.Path -> path,
    AuthorityHeader,
    ContentTypeHeader,
    MethodHeader,
    SchemeHeader
  )
}
