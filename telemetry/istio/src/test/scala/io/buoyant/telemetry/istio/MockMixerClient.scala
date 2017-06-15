package io.buoyant.telemetry.istio

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.H2
import com.twitter.util.Future
import istio.mixer.v1.ReportResponse

class MockMixerClient extends MixerClient(H2.client.newService("dest")) {
  var applies = 0

  override def apply(): Future[ReportResponse] = {
    applies += 1
    Future.value(ReportResponse())
  }
}
