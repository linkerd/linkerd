package io.buoyant.router.http

/**
  * Type class for HTTP 1.1/2 requests
  * @tparam R Request type
  * @tparam H Headers object type
  */
abstract class RequestLike[R, H: HeadersLike] {
  def headers(request: R): H
}
