package io.buoyant.k8s.istio

import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.Service
import com.twitter.util.{Duration, Future}
import io.buoyant.test.{Awaits, Exceptions}
import istio.mixer.v1.ReportResponse
import org.scalatest.FunSuite

class MixerClientTest extends FunSuite with Awaits with Exceptions {

  var calls = 0
  val service = Service.mk[h2.Request, h2.Response] { req =>
    calls += 1
    Future.value(h2.Response(h2.Status.Ok, h2.Stream()))
  }

  test("report makes a service call") {
    val mixerClient = new MixerClient(service)
    assert(calls == 0)
    val rsp = mixerClient.report(
      ResponseCodeIstioAttribute(200),
      RequestPathIstioAttribute("requestPath"),
      TargetServiceIstioAttribute("targetService"),
      SourceLabelIstioAttribute(Map.empty),
      TargetLabelsIstioAttribute(Map.empty),
      ResponseDurationIstioAttribute(Duration.Zero)
    )
    assert(calls == 1)
  }
}
