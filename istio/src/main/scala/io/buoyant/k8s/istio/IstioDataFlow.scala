package io.buoyant.k8s.istio

import com.twitter.finagle.Path
import io.buoyant.router.context.DstBoundCtx

/**
 * An abstraction applicable to both Requests and Responses in an Istio service mesh.
 *
 * In an Istio setup, both requests and responses are augmented with [[IstioAttribute]]s that can
 * be used in various ways. While some attributes are specific to either requests or responses, others
 * are common in both types of objects.
 *
 */
trait IstioDataFlow {
  val unknown = "unknown"

  // expected istioPath
  // Path(%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http)
  private val pathLength = 10
  private val pathServiceIndex = 7
  private val pathLabelsIndex = 8

  /*
    * Returns the Istio path that returned this response, if any.
    *
    * Checks the current [[com.twitter.finagle.context.Context]] _at instantiation time_ to verify
    * if the Istio path was resolved or nor
    */
  protected val istioPath = DstBoundCtx.current.flatMap { bound =>
    bound.id match {
      case path: Path if (path.elems.length == pathLength) => Some(path)
      case _ => None
    }
  }

  private def findTargetService: Option[String] = istioPath.map { path =>
    path.showElems(pathServiceIndex)
  }

  private def findTargetVersion: Option[String] = istioPath match {
    case Some(path) =>
      path.showElems(pathLabelsIndex).split("::").find {
        e => e.startsWith("version:")
      }.map { label => label.split(":").last }
    case _ => None
  }

  /* note that this should really come from the "app" label of the target pod.
   in most cases this will be the same value, but eventually the correct solution
   should be to get this directly from the label or the uid.
   */
  private def findTargetLabelApp: Option[String] = findTargetService.flatMap(_.split('.').headOption)

  def targetService: TargetServiceIstioAttribute = TargetServiceIstioAttribute(findTargetService.getOrElse(unknown))

  def sourceLabel: SourceLabelIstioAttribute = SourceLabelIstioAttribute(Map(
    "app" -> unknown,
    "version" -> unknown
  ))

  def targetLabel: TargetLabelsIstioAttribute = TargetLabelsIstioAttribute((Map(
    "app" -> findTargetLabelApp.getOrElse(unknown),
    "version" -> findTargetVersion.getOrElse(unknown)
  )))
}
