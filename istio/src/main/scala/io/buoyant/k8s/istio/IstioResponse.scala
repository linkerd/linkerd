package io.buoyant.k8s.istio

import com.twitter.util.Duration

case class IstioResponse(statusCode: Int, duration: Duration) extends IstioDataFlow {

  def responseCode: ResponseCodeIstioAttribute = ResponseCodeIstioAttribute(statusCode)

  def responseDuration: ResponseDurationIstioAttribute = ResponseDurationIstioAttribute(duration)

}
