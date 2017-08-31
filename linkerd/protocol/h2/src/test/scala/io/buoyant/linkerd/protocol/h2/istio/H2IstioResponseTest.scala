package io.buoyant.linkerd.protocol.h2.istio

import com.twitter.finagle.buoyant.h2.{Response, Status}
import com.twitter.util.{Duration, Return, Throw}
import io.buoyant.k8s.istio.{ResponseDurationIstioAttribute, SourceLabelIstioAttribute, TargetLabelsIstioAttribute, TargetServiceIstioAttribute}
import org.scalatest.FunSuite
import com.twitter.finagle.buoyant.h2.Stream

class H2IstioResponseTest extends FunSuite {
  {
    test("generates istio response with expected attributes") {
      val httpResponse = Response(Status.Ok, Stream())
      val duration = Duration.Top
      val response = Return(httpResponse)
      val istioResponse = H2IstioResponse(response, duration)

      assert(istioResponse.statusCode == httpResponse.status.code)

      assert(istioResponse.targetService == TargetServiceIstioAttribute("unknown"))
      assert(istioResponse.sourceLabel.value == SourceLabelIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
      assert(istioResponse.targetLabel.value == TargetLabelsIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
      assert(istioResponse.duration == ResponseDurationIstioAttribute(duration).value)
    }

    test("generates istio response for failed response") {
      val httpResponse = Response(Status.Ok, Stream())
      val duration = Duration.Top
      val response = Throw(new UnsupportedOperationException("not implemented"))
      val istioResponse = H2IstioResponse(response, duration)

      assert(istioResponse.statusCode == Status.InternalServerError.code)

      assert(istioResponse.targetService == TargetServiceIstioAttribute("unknown"))
      assert(istioResponse.sourceLabel.value == SourceLabelIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
      assert(istioResponse.targetLabel.value == TargetLabelsIstioAttribute(Map("app" -> "unknown", "version" -> "unknown")).value)
      assert(istioResponse.duration == ResponseDurationIstioAttribute(duration).value)
    }
  }
}
