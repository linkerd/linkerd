package io.buoyant.k8s.istio

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2
import com.twitter.logging.Logger
import com.twitter.util.Duration
import io.buoyant.grpc.runtime.Stream
import istio.mixer.v1.{Mixer, ReportResponse}

case class MixerClient(service: Service[h2.Request, h2.Response]) {
  private[this] val log = Logger()

  private[this] val client = new Mixer.Client(service)

  def report(
    responseCode: Int,
    requestPath: String,
    targetService: String,
    sourceLabelApp: String,
    targetLabelApp: String,
    targetLabelVersion: String,
    duration: Duration
  ): Stream[ReportResponse] = {

    val reportRequest = MixerApiRequests.mkReportRequest(
      responseCode,
      requestPath,
      targetService,
      sourceLabelApp,
      targetLabelApp,
      targetLabelVersion,
      duration
    )
    log.trace("MixerClient.report: %s", reportRequest)
    client.report(Stream.value(reportRequest))
  }
}
