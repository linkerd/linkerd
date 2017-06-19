package io.buoyant.k8s.istio

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.local.DurationProto.{Duration => gDuration}
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.Service
import com.twitter.logging.Logger
import com.twitter.util.{Duration, Future}
import io.buoyant.grpc.runtime.Stream
import istio.mixer.v1.{Attributes, Mixer, ReportRequest, ReportResponse, StringMap}
import scala.io.Source

private[istio] object MixerClient {

  private[this] lazy val globalDict: Seq[String] = {
    val yaml = Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("mixer/v1/global_dictionary.yaml")
    ).mkString

    val mapper = new ObjectMapper(new YAMLFactory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    mapper.readValue[Seq[String]](yaml).asInstanceOf[Seq[String]]
  }

  private[istio] val log = Logger()

  // minimum set of attributes to generate the following metrics in mixer/prometheus:
  // - request_count
  // - request_duration_bucket
  // - request_duration_count
  // - request_duration_sum
  //
  // example metrics exposed by mixer/prometheus:
  // request_count{method="/productpage",response_code="200",service="productpage",source="unknown",target="productpage.default.svc.cluster.local",version="v1"} 327
  // request_count{method="/reviews",response_code="500",service="reviews",source="productpage",target="reviews.default.svc.cluster.local",version="v1"} 1
  private[istio] def mkReportRequest(
    responseCode: Int,
    requestPath: String,
    targetService: String,
    sourceLabelApp: String,
    targetLabelApp: String,
    targetLabelVersion: String,
    duration: gDuration
  ): ReportRequest = {

    val customWords = Seq(
      "app",
      "version",
      requestPath,
      targetService,
      sourceLabelApp,
      targetLabelApp,
      targetLabelVersion
    )

    // TODO: eventually we should not have to send globalDict over the wire,
    // and instead use positive index integers
    val defaultWords = globalDict ++ customWords
    ReportRequest(
      attributeUpdate = Some(
        Attributes(
          dictionary = (defaultWords.indices.zip(defaultWords)).toMap,

          stringAttributes = Map[Int, String](
            defaultWords.indexOf("request.path") -> requestPath, // method in prom
            defaultWords.indexOf("target.service") -> targetService // target in prom
          ),
          int64Attributes = Map[Int, Long](
            defaultWords.indexOf("response.code") -> responseCode // response_code in prom
          ),
          // TODO: send source.uid instead of labels, Mixer will populate them for us
          stringMapAttributes = Map[Int, StringMap](
            defaultWords.indexOf("source.labels") ->
              StringMap(
                map = Map[Int, String](
                  defaultWords.indexOf("app") -> sourceLabelApp // source in prom
                )
              ),
            defaultWords.indexOf("target.labels") ->
              StringMap(
                map = Map[Int, String](
                  defaultWords.indexOf("app") -> targetLabelApp, // service in prom
                  defaultWords.indexOf("version") -> targetLabelVersion // version in prom
                )
              )
          ),
          durationAttributesHACK = Map[Int, gDuration](
            defaultWords.indexOf("response.duration") -> duration // request_duration_[bucket|count|sum] in prom
          )
        )
      )
    )
  }
}

case class MixerClient(service: Service[h2.Request, h2.Response]) {
  import MixerClient._

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
    val reportRequest =
      mkReportRequest(
        responseCode,
        requestPath,
        targetService,
        sourceLabelApp,
        targetLabelApp,
        targetLabelVersion,
        gDuration(
          seconds = Some(duration.inLongSeconds),
          nanos = Some((duration.inNanoseconds - duration.inLongSeconds * 1000000000L).toInt)
        )
      )
    log.trace(s"MixerClient.report: $reportRequest")
    client.report(Stream.value(reportRequest))
  }
}
