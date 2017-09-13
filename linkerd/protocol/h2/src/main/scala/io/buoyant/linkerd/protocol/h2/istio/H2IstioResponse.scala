package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.buoyant.h2.Response
import com.twitter.finagle.http.Status
import com.twitter.util.{Duration, Try}
import io.buoyant.k8s.istio.IstioResponse

object H2IstioResponse {
  def apply(resp: Try[Response], duration: Duration): IstioResponse[Response] = {
    val maybeInt = resp.toOption.map(_.status.code)
    new IstioResponse(maybeInt.getOrElse(Status.InternalServerError.code), duration, resp.toOption)
  }
}
