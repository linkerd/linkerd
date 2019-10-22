package io.buoyant.k8s.istio

import com.twitter.finagle.{Filter, Service}
import com.twitter.logging.Logger
import com.twitter.util.{Duration, Future, Stopwatch, Try}
import io.buoyant.k8s.istio.mixer.MixerClient

abstract class IstioRequestAuthorizerFilter[Req, Resp](mixerClient: MixerClient) extends Filter[Req, Resp, Req, Resp] {
  val logger = Logger()

  def toIstioRequest(req: Req): IstioRequest[Req]

  def toIstioResponse(resp: Try[Resp], duration: Duration): IstioResponse[Resp]

  def toFailedResponse(code: Int, reason: String): Resp

  def apply(req: Req, svc: Service[Req, Resp]): Future[Resp] = {
    val istioRequest = toIstioRequest(req)

    logger.trace("checking Istio pre-conditions for request %s", istioRequest)
    mixerClient.checkPreconditions(istioRequest).flatMap { status =>
      if (status.success) {
        logger.trace("Successful pre-condition check for request: %s", istioRequest)
        callService(req, svc, istioRequest)
      } else {
        logger.info("request [%s] failed Istio pre-condition check: %s", istioRequest, status)
        Future.value(toFailedResponse(status.httpCode, status.reason))
      }
    }
  }

  def report(request: IstioRequest[Req], response: IstioResponse[Resp], duration: Duration): Future[Unit] = {
    mixerClient.report(
      response.responseCode,
      request.requestedPath,
      request.targetService,
      request.sourceLabel,
      request.targetLabel,
      response.responseDuration
    )
  }

  private def callService(req: Req, svc: Service[Req, Resp], istioRequest: IstioRequest[Req]) = {
    val elapsed = Stopwatch.start()

    svc(req).respond { resp =>

      val duration = elapsed()
      val istioResponse = toIstioResponse(resp, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }
}