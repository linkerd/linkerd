package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.Path
import com.twitter.finagle.http.Request
import io.buoyant.k8s.istio.IstioRequest

object HttpIstioRequest {
  def apply(req: Request, istioPath: Option[Path]): IstioRequest[Request] =
    //TODO: match on request scheme
    IstioRequest(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get, req, istioPath)
}

