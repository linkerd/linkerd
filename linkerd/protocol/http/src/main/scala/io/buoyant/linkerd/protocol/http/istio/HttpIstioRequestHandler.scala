package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.k8s.istio.identifiers.IstioProtocolSpecificRequestHandler
import io.buoyant.linkerd.protocol.http.ErrorResponder.HttpResponseException
import istio.proxy.v1.config.HTTPRedirect

class HttpIstioRequestHandler extends IstioProtocolSpecificRequestHandler[Request] {
  def redirectRequest(redir: HTTPRedirect, req: Request): Future[Nothing] = {
    val redirect = Response(Status.Found)
    redirect.location = redir.`uri`.getOrElse(req.uri)
    redirect.host = redir.`authority`.orElse(req.host).getOrElse("")
    Future.exception(HttpResponseException(redirect))
  }

  def rewriteRequest(uri: String, authority: Option[String], req: Request): Unit = {
    req.uri = uri
    req.host = authority.getOrElse("")
  }
}
