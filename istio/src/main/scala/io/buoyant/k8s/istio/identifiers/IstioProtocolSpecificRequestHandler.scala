package io.buoyant.k8s.istio.identifiers

import com.twitter.util.Future
import istio.proxy.v1.config.HTTPRedirect

trait IstioProtocolSpecificRequestHandler[Req] {
  def redirectRequest(redir: HTTPRedirect, req: Req): Future[Nothing]
  def rewriteRequest(uri: String, authority: Option[String], req: Req): Unit
}
