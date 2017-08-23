package io.buoyant.k8s.istio

import com.google.local.DurationProto.{Duration => GDuration}
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.util.Duration
import istio.mixer.v1.{Attributes, ReportRequest, StringMap}

import scala.io.Source

object MixerApiRequests {

  private[this] lazy val istioGlobalDict: Seq[String] = {
    val yaml = Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("mixer/v1/global_dictionary.yaml")
    ).mkString

    val mapper = new ObjectMapper(new YAMLFactory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    mapper.readValue[Seq[String]](yaml).asInstanceOf[Seq[String]]
  }

  // minimum set of attributes to generate the following metrics in mixer/prometheus:
  // - request_count
  // - request_duration_bucket
  // - request_duration_count
  // - request_duration_sum
  //
  // example metrics exposed by mixer/prometheus:
  // request_count{method="/productpage",response_code="200",service="productpage",source="unknown",target="productpage.default.svc.cluster.local",version="v1"} 327
  // request_count{method="/reviews",response_code="500",service="reviews",source="productpage",target="reviews.default.svc.cluster.local",version="v1"} 1
  def mkReportRequest(
    responseCode: Int,
    requestPath: String,
    targetService: String,
    sourceLabelApp: String,
    targetLabelApp: String,
    targetLabelVersion: String,
    duration: Duration
  ): ReportRequest = {

    //TODO: when we first added Istio integration, this was required to make the integration work. We need to double-check if we can avoid sending these extra entries
    val customWords = Seq(
      "app",
      "version",
      requestPath,
      targetService,

      sourceLabelApp,

      targetLabelApp,
      targetLabelVersion
    )

    val updated = mkAttributesUpdate(customWords, responseCode, requestPath, targetService, sourceLabelApp, targetLabelApp, targetLabelVersion, duration)
    ReportRequest(attributeUpdate = Some(updated))
  }

  private def mkAttributesUpdate(customAttributeNames: Seq[String], responseCode: Int, requestPath: String, targetService: String, sourceLabelApp: String, targetLabelApp: String, targetLabelVersion: String, duration: Duration) = {

    // TODO: eventually we should not have to send istioGlobalDict over the wire,
    // and instead use positive index integers
    val dictionaryToUse = istioGlobalDict ++ customAttributeNames

    val gDuration = GDuration(
      seconds = Some(duration.inLongSeconds),
      nanos = Some((duration.inNanoseconds - duration.inLongSeconds * 1000000000L).toInt)
    )

    Attributes(
      dictionary = (dictionaryToUse.indices.zip(dictionaryToUse)).toMap,

      stringAttributes = Map[Int, String](
        dictionaryToUse.indexOf("request.path") -> requestPath, // method in prom
        dictionaryToUse.indexOf("target.service") -> targetService // target in prom
      ),
      int64Attributes = Map[Int, Long](
        dictionaryToUse.indexOf("response.code") -> responseCode // response_code in prom
      ),
      // TODO: send source.uid instead of labels, Mixer will populate them for us
      stringMapAttributes = Map[Int, StringMap](
        dictionaryToUse.indexOf("source.labels") ->
          StringMap(
            map = Map[Int, String](
              dictionaryToUse.indexOf("app") -> sourceLabelApp // source in prom
            )
          ),
        dictionaryToUse.indexOf("target.labels") ->
          StringMap(
            map = Map[Int, String](
              dictionaryToUse.indexOf("app") -> targetLabelApp, // service in prom
              dictionaryToUse.indexOf("version") -> targetLabelVersion // version in prom
            )
          )
      ),
      durationAttributesHACK = Map[Int, GDuration](
        dictionaryToUse.indexOf("response.duration") -> gDuration // request_duration_[bucket|count|sum] in prom
      )
    )
  }
}
