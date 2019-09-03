package io.buoyant.grpc.runtime

import com.twitter.finagle.buoyant.h2

private[runtime] object H2Headers {
  private[this] val ContentType = "content-type"
  private[this] val GrpcProtoContentType = "application/grpc+proto"

  def responseHeaders(http2Status: h2.Status = h2.Status.Ok): h2.Headers = h2.Headers(
    h2.Headers.Status -> http2Status.toString,
    ContentType -> GrpcProtoContentType
  )

  def requestHeaders(path: String): h2.Headers = h2.Headers(
    h2.Headers.Scheme -> "http",
    h2.Headers.Method -> h2.Method.Post.toString,
    h2.Headers.Authority -> "",
    h2.Headers.Path -> path,
    ContentType -> GrpcProtoContentType
  )
}
