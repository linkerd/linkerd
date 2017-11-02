package io.buoyant.k8s.istio

import com.twitter.util.Duration

case class IstioResponse[Resp](statusCode: Int, duration: Duration, resp: Option[Resp]) {

  def responseCode: ResponseCodeIstioAttribute = ResponseCodeIstioAttribute(statusCode)

  def responseDuration: ResponseDurationIstioAttribute = ResponseDurationIstioAttribute(duration)

}
