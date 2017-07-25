package io.buoyant.k8s.istio

import com.twitter.finagle._
import com.twitter.util.Duration

trait IstioLoggerBase {
  val unknown = "unknown"

  // expected istioPath
  // Path(%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http)
  val pathLength = 10
  val pathServiceIndex = 7
  val pathLabelsIndex = 8

  def targetService(istioPath: Option[Path]): Option[String] = istioPath.map { path =>
    path.showElems(pathServiceIndex)
  }

  def version(istioPath: Option[Path]): Option[String] = istioPath match {
    case Some(path) =>
      path.showElems(pathLabelsIndex).split("::").find {
        e => e.startsWith("version:")
      }.map { label => label.split(":").last }
    case _ => None
  }

  // note that this should really come from the "app" label of the target pod.
  // in most cases this will be the same value, but eventually the correct solution
  // should be to get this directly from the label or the uid.
  def targetLabelApp(targetService: Option[String]): Option[String] = targetService.flatMap(_.split('.').headOption)

  def mixerClient: MixerClient

  def report(istioPath: Option[Path], responseCode: Int, path: String, duration: Duration) = {
    val tService = targetService(istioPath)
    mixerClient.report(
      responseCode,
      path,
      tService.getOrElse(unknown), // target in prom (~reviews.default.svc.cluster.local)
      unknown, // source in prom (~productpage)
      targetLabelApp(tService).getOrElse(unknown), // service in prom (~reviews)
      version(istioPath).getOrElse(unknown), // version in prom (~v1)
      duration
    )
  }
}
