package io.buoyant.k8s.istio

import com.twitter.finagle.Service
import com.twitter.finagle.buoyant.h2
import com.twitter.logging.Logger
import io.buoyant.grpc.runtime.Stream
import io.buoyant.k8s.istio.mixer.MixerApiRequests
import istio.mixer.v1.{Mixer, ReportResponse}

case class MixerClient(service: Service[h2.Request, h2.Response]) {
  private[this] val log = Logger()

  private[this] val client = new Mixer.Client(service)

  // minimum set of attributes to generate the following metrics in mixer/prometheus:
  // - request_count
  // - request_duration_bucket
  // - request_duration_count
  // - request_duration_sum
  //
  // example metrics exposed by mixer/prometheus:
  // request_count{method="/productpage",response_code="200",service="productpage",source="unknown",target="productpage.default.svc.cluster.local",version="v1"} 327
  // request_count{method="/reviews",response_code="500",service="reviews",source="productpage",target="reviews.default.svc.cluster.local",version="v1"} 1
  def report(
    responseCode: ResponseCodeIstioAttribute,
    requestPath: RequestPathIstioAttribute,
    targetService: TargetServiceIstioAttribute,
    sourceLabel: SourceLabelIstioAttribute,
    targetLabel: TargetLabelsIstioAttribute,
    duration: ResponseDurationIstioAttribute
  ): Stream[ReportResponse] = {

    val reportRequest = MixerApiRequests.mkReportRequest(
      responseCode,
      requestPath,
      targetService,
      sourceLabel,
      targetLabel,
      duration
    )
    log.trace("MixerClient.report: %s", reportRequest)
    client.report(Stream.value(reportRequest))
  }
}
