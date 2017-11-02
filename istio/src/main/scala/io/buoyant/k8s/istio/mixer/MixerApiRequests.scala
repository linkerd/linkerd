package io.buoyant.k8s.istio.mixer

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.local.DurationProto.{Duration => GDuration}
import com.twitter.util.Duration
import io.buoyant.k8s.istio.{StringAttribute, _}
import istio.mixer.v1._
import scala.io.Source

object MixerApiRequests {
  val requestIndexIsIrrelevant = Some(210283L)

  private[this] lazy val istioGlobalDict: Seq[String] = {
    val yaml = Source.fromInputStream(
      getClass.getClassLoader.getResourceAsStream("mixer/v1/global_dictionary.yaml")
    ).mkString

    val mapper = new ObjectMapper(new YAMLFactory) with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    mapper.readValue[Seq[String]](yaml)
  }

  def mkReportRequest(
    responseCode: ResponseCodeIstioAttribute,
    requestPath: RequestPathIstioAttribute,
    targetService: TargetServiceIstioAttribute,
    sourceLabel: SourceLabelIstioAttribute,
    targetLabel: TargetLabelsIstioAttribute,
    duration: ResponseDurationIstioAttribute
  ): ReportRequest = {

    val dictionary = mkDictionary(requestPath, targetService, sourceLabel, targetLabel)

    val updated = mkAttributesUpdate(dictionary, Seq(responseCode, requestPath, targetService, sourceLabel, targetLabel, duration))
    ReportRequest(requestIndexIsIrrelevant, Some(updated))
  }

  def mkCheckRequest(istioRequest: IstioRequest[_]) = {
    val dictionary = mkDictionary(istioRequest.requestedPath, istioRequest.targetService, istioRequest.sourceLabel, istioRequest.targetLabel)
    val requestAttributes = Seq(istioRequest.requestedPath, istioRequest.sourceLabel, istioRequest.targetLabel, istioRequest.targetService)
    val attributesUpdate = mkAttributesUpdate(dictionary, requestAttributes)
    CheckRequest(requestIndexIsIrrelevant, Some(attributesUpdate))
  }

  private def mkDictionary(
    requestPath: RequestPathIstioAttribute,
    targetService: TargetServiceIstioAttribute,
    sourceLabel: SourceLabelIstioAttribute,
    targetLabel: TargetLabelsIstioAttribute
  ) = {
    val targetLabelApp = targetLabel.value.getOrElse("app", "")
    val targetLabelVersion = targetLabel.value.getOrElse("version", "")
    val sourceLabelApp = sourceLabel.value.getOrElse("app", "")
    val sourceLabelVersion = sourceLabel.value.getOrElse("version", "")

    //TODO: when we first added Istio integration, this was required to make the integration work. We need to double-check if we can avoid sending these extra entries
    val customWords = Seq(
      "app",
      "version",
      requestPath.name,
      targetService.name,
      targetLabelApp,
      targetLabelVersion,
      sourceLabelApp,
      sourceLabelVersion
    ) ++ sourceLabel.value.keySet ++ targetLabel.value.keySet
    customWords
  }

  private def mkAttributesUpdate(customAttributeNames: Seq[String], attributes: Seq[IstioAttribute[_]]) = {

    // TODO: eventually we should not have to send istioGlobalDict over the wire,
    // and instead use positive index integers
    val dictionaryToUse = (istioGlobalDict ++ customAttributeNames).toSet.toSeq

    val allStringAttributes = attributes.collect { case attr: StringAttribute => attr }
    val allInt64Attributes = attributes.collect { case attr: Int64Attribute => attr }
    val allStringMapAttributes = attributes.collect { case attr: StringMapAttribute => attr }
    val allDurationAttributes = attributes.collect { case attr: DurationAttribute => attr }

    Attributes(
      dictionary = (dictionaryToUse.indices.zip(dictionaryToUse)).toMap,

      stringAttributes = allStringAttributes.foldLeft(Map[Int, String]()) {
        (map, attr) => map + (dictionaryToUse.indexOf(attr.name) -> attr.value)
      },

      int64Attributes = allInt64Attributes.foldLeft(Map[Int, Long]()) {
        (map, attr) => map + (dictionaryToUse.indexOf(attr.name) -> attr.value)
      },

      durationAttributesHACK = allDurationAttributes.foldLeft(Map[Int, GDuration]()) {
        (map, attr) =>
          val duration = attr.value
          val gDuration = GDuration(
            seconds = Some(duration.inLongSeconds),
            nanos = Some((duration.inNanoseconds - duration.inLongSeconds * 1000000000L).toInt)
          )

          map + (dictionaryToUse.indexOf(attr.name) -> gDuration)
      },

      stringMapAttributes = allStringMapAttributes.foldLeft(Map[Int, StringMap]()) {
        (map, attr) =>

          val valueAsIndexedMap = attr.value.foldLeft(Map[Int, String]()) {
            (m, kv) =>
              m + (dictionaryToUse.indexOf(kv._1) -> kv._2)
          }

          map + (dictionaryToUse.indexOf(attr.name) -> StringMap(map = valueAsIndexedMap))
      }
    )
  }
}
