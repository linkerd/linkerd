package io.buoyant.k8s.istio

import com.twitter.finagle.{Filter, Service}
import com.twitter.util.{Duration, Stopwatch, Try}
import io.buoyant.k8s.istio.mixer.MixerClient

abstract class IstioRequestAuthorizerFilter[Req, Resp](mixerClient: MixerClient) extends Filter[Req, Resp, Req, Resp] {

  def report(request: IstioRequest[Req], response: IstioResponse[Resp], duration: Duration) = {

    mixerClient.report(
      response.responseCode,
      request.requestedPath,
      request.targetService,
      request.sourceLabel,
      request.targetLabel,
      response.responseDuration
    )
  }

  def toIstioRequest(req: Req): IstioRequest[Req]

  def toIstioResponse(resp: Try[Resp], duration: Duration): IstioResponse[Resp]

  def apply(req: Req, svc: Service[Req, Resp]) = {
    val istioRequest = toIstioRequest(req)

    val elapsed = Stopwatch.start()

    svc(req).respond { resp =>

      val duration = elapsed()
      val istioResponse = toIstioResponse(resp, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }
}