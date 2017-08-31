package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http._
import com.twitter.util.{Duration, Return, Throw}
import io.buoyant.k8s.istio._
import org.scalatest.FunSuite

class HttpIstioResponseTest extends FunSuite {
  test("generates istio response with expected attributes") {
    val httpResponse = Response(Version.Http11, Status.Ok)
    val duration = Duration.Top
    val response = Return(httpResponse)
    val istioResponse = HttpIstioResponse(response, duration)

    assert(istioResponse.statusCode == httpResponse.statusCode)

    assert(istioResponse.targetService == TargetServiceIstioAttribute("unknown"))
    assert(istioResponse.sourceLabel.value == SourceLabelIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
    assert(istioResponse.targetLabel.value == TargetLabelsIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
    assert(istioResponse.duration == ResponseDurationIstioAttribute(duration).value)
  }

  test("generates istio response for failed response") {
    val httpResponse = Response()
    val duration = Duration.Top
    val response = Throw(new UnsupportedOperationException("not implemented"))
    val istioResponse = HttpIstioResponse(response, duration)

    assert(istioResponse.statusCode == Status.InternalServerError.code)

    assert(istioResponse.targetService == TargetServiceIstioAttribute("unknown"))
    assert(istioResponse.sourceLabel.value == SourceLabelIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
    assert(istioResponse.targetLabel.value == TargetLabelsIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
    assert(istioResponse.duration == ResponseDurationIstioAttribute(duration).value)
  }

}
