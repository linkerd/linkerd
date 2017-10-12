package io.buoyant.k8s.istio

import com.twitter.finagle.Path

/**
 * Defines a request's metadata for istio rules to match against
 * (normalizes fields between http and h2)
 */
case class IstioRequest[Req](
  uri: String,
  scheme: String,
  method: String,
  authority: String,
  getHeader: (String) => Option[String],
  req: Req,
  istioPath: Option[Path]
) {
  val unknown = "unknown"

  val requestedPath: RequestPathIstioAttribute = RequestPathIstioAttribute(uri)

  val targetService: TargetServiceIstioAttribute = {
    val foundService = istioPath.flatMap(CurrentIstioPath.targetServiceIn(_))
    TargetServiceIstioAttribute(foundService.getOrElse(unknown))
  }

  val sourceLabel: SourceLabelIstioAttribute = SourceLabelIstioAttribute(Map(
    "app" -> unknown,
    "version" -> unknown
  ))

  val targetLabel: TargetLabelsIstioAttribute = {
    val app = istioPath.flatMap(CurrentIstioPath.targetAppIn(_))
    val version = istioPath.flatMap(CurrentIstioPath.targetVersionIn(_))

    TargetLabelsIstioAttribute(Map(
      "app" -> app.getOrElse(unknown),
      "version" -> version.getOrElse(unknown)
    ))
  }
}

