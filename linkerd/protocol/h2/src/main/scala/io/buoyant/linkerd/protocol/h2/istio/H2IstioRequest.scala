package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.buoyant.h2.Request
import io.buoyant.k8s.istio.IstioRequest

object H2IstioRequest {
  def apply(req: Request): IstioRequest = new IstioRequest(req.path, req.scheme, req.method.toString, req.authority, req.headers.get)
}
