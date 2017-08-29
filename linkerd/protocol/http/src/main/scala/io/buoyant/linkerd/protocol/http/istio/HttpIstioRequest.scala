package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http.Request
import io.buoyant.k8s.istio.IstioRequest

object HttpIstioRequest {
  def apply(req: Request): IstioRequest = IstioRequest(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get)
}
