package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http.{Response, Status}
import com.twitter.util.{Duration, Try}
import io.buoyant.k8s.istio.IstioResponse

object HttpIstioResponse {
  def apply(resp: Try[Response], duration: Duration): IstioResponse = {
    val status = resp.toOption.map(_.status).getOrElse(Status.InternalServerError)
    new IstioResponse(status.code, duration)
  }
}
