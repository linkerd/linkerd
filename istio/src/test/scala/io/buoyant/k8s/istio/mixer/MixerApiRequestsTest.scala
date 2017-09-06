package io.buoyant.k8s.istio.mixer

import java.util.concurrent.TimeUnit

import com.google.local.DurationProto.{Duration => GDuration}
import com.twitter.util.Duration
import io.buoyant.k8s.istio._
import istio.mixer.v1._
import org.scalatest.FunSuite

class MixerApiRequestsTest extends FunSuite {

  test("report - the key of every serialised attribute can be resolved using the dictionary") {
    val reportRequest = MixerApiRequests.mkReportRequest(
      ResponseCodeIstioAttribute(200),
      RequestPathIstioAttribute("requestPath"),
      TargetServiceIstioAttribute("targetService"),
      SourceLabelIstioAttribute(Map.empty),
      TargetLabelsIstioAttribute(Map.empty),
      ResponseDurationIstioAttribute(Duration.Zero)
    )

    assertCanResolveAllAttributesInDictionary(reportRequest.`attributeUpdate`)
  }

  test("report - all expected attributes are serialised") {
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

  test("check- the key of every serialised attribute can be resolved using the dictionary") {
    val istioRequest = IstioRequest(
      "/users/123",
      "http",
      "POST",
      "clientsvc",
      (_) => None
    )
    val checkRequest = MixerApiRequests.mkCheckRequest(istioRequest)

    assertCanResolveAllAttributesInDictionary(checkRequest.`attributeUpdate`)
  }

  test("check - all expected attributes are serialised") {
    val istioRequest = IstioRequest(
      "/users/123",
      "http",
      "POST",
      "clientsvc",
      (_) => None
    )
    val reportRequest = MixerApiRequests.mkCheckRequest(istioRequest)

    val updateBody = reportRequest.`attributeUpdate`.get
    val allAttributesSent = allUpdatedAttributesIn(updateBody)
    val reverseDictionary = updateBody.`dictionary`.groupBy(_._2).mapValues(_.map(_._1))

    assert(allAttributesSent(reverseDictionary("request.path").head) === "/users/123")
    assert(allAttributesSent(reverseDictionary("target.service").head) === "unknown")

    val expectedSourceLabels = StringMap(map = Map[Int, String](
      reverseDictionary("app").head -> "unknown",
      reverseDictionary("version").head -> "unknown"
    ))
    assert(allAttributesSent(reverseDictionary("source.labels").head).asInstanceOf[StringMap].`map` == expectedSourceLabels.`map`)

    val expectedTargetLabels = StringMap(map = Map[Int, String](
      reverseDictionary("app").head -> "unknown",
      reverseDictionary("version").head -> "unknown"
    ))
    assert(allAttributesSent(reverseDictionary("target.labels").head).asInstanceOf[StringMap].`map` == expectedTargetLabels.`map`)
    pending // ("all these unknbowns?")
  }

  private def assertCanResolveAllAttributesInDictionary(updateBody: Option[Attributes]) = {
    assert(updateBody.isDefined)
    val attributes = updateBody.get
    val allAttributesSent = allUpdatedAttributesIn(attributes)
    val dictionarySent = attributes.`dictionary`
    assert(allAttributesSent.keySet.subsetOf(dictionarySent.keySet))
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
