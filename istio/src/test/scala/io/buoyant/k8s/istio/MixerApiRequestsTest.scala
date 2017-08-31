package io.buoyant.k8s.istio

import java.util.concurrent.TimeUnit

import com.google.local.DurationProto.{Duration => GDuration}
import com.twitter.util.Duration
import io.buoyant.k8s.istio.mixer.MixerApiRequests
import istio.mixer.v1.{Attributes, StringMap}
import org.scalatest.FunSuite

class MixerApiRequestsTest extends FunSuite {
  test("the key of every serialised attribute can be resolved using the dictionary") {
    val mixerApiRequest = MixerApiRequests.mkReportRequest(
      ResponseCodeIstioAttribute(200),
      RequestPathIstioAttribute("requestPath"),
      TargetServiceIstioAttribute("targetService"),
      SourceLabelIstioAttribute(Map.empty),
      TargetLabelsIstioAttribute(Map.empty),
      ResponseDurationIstioAttribute(Duration.Zero)
    )

    val updateBody = mixerApiRequest.`attributeUpdate`.get
    val allAttributesSent = allUpdatedAttributesIn(updateBody)
    val dictionarySent = updateBody.`dictionary`

    assert(allAttributesSent.keySet.subsetOf(dictionarySent.keySet))
  }

  test("all expected attributes are serialised") {
    val mixerApiRequest = MixerApiRequests.mkReportRequest(
      ResponseCodeIstioAttribute(200),
      RequestPathIstioAttribute("requestPathValue"),
      TargetServiceIstioAttribute("targetServiceValue"),
      SourceLabelIstioAttribute(Map("app" -> "sourceAppValue", "version" -> "sourceAppVersionValue")),
      TargetLabelsIstioAttribute(Map("app" -> "targetAppValue", "version" -> "targetAppVersionValue")),
      ResponseDurationIstioAttribute(Duration(10, TimeUnit.SECONDS).plus(Duration(1, TimeUnit.NANOSECONDS)))
    )

    val updateBody = mixerApiRequest.`attributeUpdate`.get
    val allAttributesSent = allUpdatedAttributesIn(updateBody)
    val reverseDictionary = updateBody.`dictionary`.groupBy(_._2).mapValues(_.map(_._1))

    assert(allAttributesSent(reverseDictionary("request.path").head) === "requestPathValue")
    assert(allAttributesSent(reverseDictionary("target.service").head) === "targetServiceValue")
    assert(allAttributesSent(reverseDictionary("response.code").head) === 200)

    val expectedSourceLabels = StringMap(map = Map[Int, String](
      reverseDictionary("app").head -> "sourceAppValue",
      reverseDictionary("version").head -> "sourceAppVersionValue"
    ))
    assert(allAttributesSent(reverseDictionary("source.labels").head).asInstanceOf[StringMap].`map` == expectedSourceLabels.`map`)

    val expectedTargetLabels = StringMap(map = Map[Int, String](
      reverseDictionary("app").head -> "targetAppValue",
      reverseDictionary("version").head -> "targetAppVersionValue"
    ))
    assert(allAttributesSent(reverseDictionary("target.labels").head).asInstanceOf[StringMap].`map` == expectedTargetLabels.`map`)

    val duration = allAttributesSent(reverseDictionary("response.duration").head).asInstanceOf[GDuration]
    assert(duration.`seconds`.get == 10)
    assert(duration.`nanos`.get == 1)
  }

  private def allUpdatedAttributesIn(updateBody: Attributes) = {
    val allAttributesSent =
      updateBody.`stringAttributes` ++
        updateBody.`int64Attributes` ++
        updateBody.`stringMapAttributes` ++
        updateBody.`durationAttributesHACK` ++
        updateBody.`bytesAttributes` ++
        updateBody.`doubleAttributes` ++
        updateBody.`durationAttributes` ++
        updateBody.`doubleAttributes`
    allAttributesSent
  }
}
