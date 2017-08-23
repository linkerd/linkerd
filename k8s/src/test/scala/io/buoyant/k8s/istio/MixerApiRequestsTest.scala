package io.buoyant.k8s.istio

import java.util.concurrent.TimeUnit

import com.google.local.DurationProto.{Duration => GDuration}
import com.twitter.util.Duration
import istio.mixer.v1.{Attributes, StringMap}
import org.scalatest.FunSuite

class MixerApiRequestsTest extends FunSuite {
  test("the key of every serialised attribute can be resolved using the dictionary") {
    val mixerApiRequest = MixerApiRequests.mkReportRequest(
      200,
      "requestPathValue",
      "targetServiceValue",
      "sourceLabelAppValue",
      "targetLabelAppValue",
      "targetLabelVersionValue",
      Duration.Zero
    )

    val updateBody = mixerApiRequest.`attributeUpdate`.get
    val allAttributesSent = allUpdatedAttributesIn(updateBody)
    val dictionarySent = updateBody.`dictionary`

    assert(allAttributesSent.keySet.subsetOf(dictionarySent.keySet))
  }

  test("all expected attributes are serialised") {
    val mixerApiRequest = MixerApiRequests.mkReportRequest(
      200,
      "requestPathValue",
      "targetServiceValue",
      "sourceLabelAppValue",
      "targetLabelAppValue",
      "targetLabelVersionValue",
      Duration(10, TimeUnit.SECONDS).plus(Duration(1, TimeUnit.NANOSECONDS))
    )

    val updateBody = mixerApiRequest.`attributeUpdate`.get
    val allAttributesSent = allUpdatedAttributesIn(updateBody)
    val reverseDictionary = updateBody.`dictionary`.groupBy(_._2).mapValues(_.map(_._1))

    assert(allAttributesSent(reverseDictionary("request.path").head) == "requestPathValue")
    assert(allAttributesSent(reverseDictionary("target.service").head) == "targetServiceValue")
    assert(allAttributesSent(reverseDictionary("response.code").head) == 200)

    val expectedSourceLabels = StringMap(map = Map[Int, String](
      reverseDictionary("app").head -> "sourceLabelAppValue"
    ))
    assert(allAttributesSent(reverseDictionary("source.labels").head) == expectedSourceLabels)

    val expectedTargetLabels = StringMap(map = Map[Int, String](
      reverseDictionary("app").head -> "targetLabelAppValue",
      reverseDictionary("version").head -> "targetLabelVersionValue"
    ))
    assert(allAttributesSent(reverseDictionary("target.labels").head) == expectedTargetLabels)

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
