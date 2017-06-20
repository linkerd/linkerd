package io.buoyant.telemetry.istio

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.local.DurationProto.Duration
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.Service
import com.twitter.util.Future
import istio.mixer.v1.{Attributes, Mixer, ReportRequest, ReportResponse, StringMap}
import scala.io.Source

private[telemetry] object MixerClient {

  private[telemetry] lazy val globalDict: Seq[String] = {
    val yaml = Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("mixer/v1/global_dictionary.yaml")
    ).mkString

    val mapper = new ObjectMapper(new YAMLFactory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    mapper.readValue[Seq[String]](yaml).asInstanceOf[Seq[String]]
  }

  private[telemetry] def indexOf(dict: Seq[String], str: String): Int =
    -1 * (dict.indexOf(str) + 1)

  private[telemetry] def mkReportRequest(): ReportRequest = {

    // TODO: build up this dictionary as requests are sent out,
    val customWords = Seq[String](
      "REQUEST_PATH",
      "buoyant.svc.cluster.local",
      "app",
      "SOURCE_LABELS_APP",
      "TARGET_LABELS_APP",
      "version",
      "TARGET_LABELS_VERSION"
    )

    // TODO: eventually we should not have to send globalDict over the wire,
    // and instead use positive index integers
    val defaultWords = globalDict ++ customWords

    ReportRequest(
      globalWordCount = Some(globalDict.length),
      defaultWords = defaultWords,
      attributes = Seq[Attributes](
        Attributes(
          // minimum set of attributes to generate the following metrics in mixer/prometheus:
          // - request_count
          // - request_duration_bucket
          // - request_duration_count
          // - request_duration_sum
          strings = Map[Int, Int](
            indexOf(defaultWords, "request.path") -> indexOf(defaultWords, "REQUEST_PATH"), // method in prom
            indexOf(defaultWords, "target.service") -> indexOf(defaultWords, "buoyant.svc.cluster.local") // target in prom
          ),
          int64s = Map[Int, Long](
            indexOf(defaultWords, "response.code") -> 200 // response_code in prom
          ),
          // TODO: send source.uid instead of labels, Mixer will populate them for us
          stringMaps = Map[Int, StringMap](
            indexOf(defaultWords, "source.labels") ->
              StringMap(
                entries = Map[Int, Int](
                  indexOf(defaultWords, "app") -> indexOf(defaultWords, "source.labels") // source in prom
                )
              ),
            indexOf(defaultWords, "target.labels") ->
              StringMap(
                entries = Map[Int, Int](
                  indexOf(defaultWords, "app") -> indexOf(defaultWords, "TARGET_LABELS_APP"), // service in prom
                  indexOf(defaultWords, "version") -> indexOf(defaultWords, "TARGET_LABELS_VERSION") // service in prom
                )
              )
          ),
          durations = Map[Int, Duration](
            indexOf(defaultWords, "response.duration") -> // request_duration_[bucket|count|sum] in prom
              Duration(
                seconds = Some(0L),
                nanos = Some(123000000)
              )
          )
        )
      )
    )
  }
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
