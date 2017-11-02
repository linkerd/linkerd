package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, Status, Stream}
import com.twitter.util.Future
import io.buoyant.k8s.istio.identifiers.IstioProtocolSpecificRequestHandler
import io.buoyant.linkerd.protocol.h2.ErrorReseter.H2ResponseException
import istio.proxy.v1.config.HTTPRedirect

class H2IstioRequestHandler extends IstioProtocolSpecificRequestHandler[Request] {
  def redirectRequest(redir: HTTPRedirect, req: Request): Future[Nothing] = {
    val resp = Response(Status.Found, Stream.empty())
    resp.headers.set(Headers.Path, redir.`uri`.getOrElse(req.path))
    resp.headers.set(Headers.Authority, redir.`authority`.getOrElse(req.authority))
    Future.exception(H2ResponseException(resp))
  }

  def rewriteRequest(uri: String, authority: Option[String], req: Request): Unit = {
    req.headers.set(Headers.Path, uri)
    req.headers.set(Headers.Authority, authority.getOrElse(""))
  }
}
