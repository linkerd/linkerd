package io.buoyant.telemetry.istio

import com.google.local.DurationProto.Duration
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.Service
import com.twitter.util.Future
import istio.mixer.v1.{Attributes, Mixer, ReportRequest, ReportResponse, StringMap}

private[telemetry] object MixerClient {

  // TODO: build up this dictionary as requests are sent out,
  // also consider using defaultWords, once per ReportRequest, rather than per Attributes
  private[telemetry] val words = Seq[String](
    // default words
    "source.ip",
    "source.port",
    "source.name",
    "source.uid",
    "source.namespace",
    "source.labels",
    "source.user",
    "target.ip",
    "target.port",
    "target.service",
    "target.name",
    "target.uid",
    "target.namespace",
    "target.labels",
    "target.user",
    "request.headers",
    "request.id",
    "request.path",
    "request.host",
    "request.method",
    "request.reason",
    "request.referer",
    "request.scheme",
    "request.size",
    "request.time",
    "request.useragent",
    "response.headers",
    "response.size",
    "response.time",
    "response.duration",
    "response.code",

    // custom words
    "REQUEST_PATH", // index -32
    "buoyant.svc.cluster.local",
    "app",
    "SOURCE_LABELS_APP",
    "TARGET_LABELS_APP",
    "version",
    "TARGET_LABELS_VERSION"
  )

  private[telemetry] def mkReportRequest(): ReportRequest =
    ReportRequest(
      attributes = Seq[Attributes](
        Attributes(
          words = words,
          // minimum set of attributes to generate the following metrics in mixer/prometheus:
          // - request_count
          // - request_duration_bucket
          // - request_duration_count
          // - request_duration_sum
          strings = Map[Int, Int](
            -18 -> -32, // request.path -> REQUEST_PATH => (method in prom)
            -10 -> -33 // target.service -> buoyant.svc.cluster.local => (target in prom)
          ),
          int64s = Map[Int, Long](
            -31 -> 200 // response.code => (response_code in prom)
          ),
          stringMaps = Map[Int, StringMap](
            -6 -> // source.labels
              StringMap(
                entries = Map[Int, Int](
                  -34 -> -35 // "app" => "SOURCE_LABELS_APP" => (source in prom)
                )
              ),
            -14 -> // target.labels
              StringMap(
                entries = Map[Int, Int](
                  -34 -> -36, // "app" => "TARGET_LABELS_APP" => (service in prom)
                  -37 -> -38 // "version" => "TARGET_LABELS_VERSION"
                )
              )
          ),
          durations = Map[Int, Duration](
            -30 -> // response.duration -> 123ms => (request_duration_[bucket|count|sum] in prom)
              Duration(
                seconds = Some(0L),
                nanos = Some(123000000)
              )
          )
        )
      )
    )
}

private[telemetry] case class MixerClient(
  service: Service[h2.Request, h2.Response]
) {
  import MixerClient._

  def apply(): Future[ReportResponse] = {
    val reportRequest = mkReportRequest()
    val client = new Mixer.Client(service)
    client.report(reportRequest)
  }
}
