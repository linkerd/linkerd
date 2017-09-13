package io.buoyant.k8s.istio

import com.twitter.finagle.Path
import com.twitter.logging.Logger
import io.buoyant.router.context.DstBoundCtx

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
  // expected istioPath
  // Path(%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http)
  private val pathServiceIndex = 7
  private val pathLabelsIndex = 8

  val requestedPath: RequestPathIstioAttribute = RequestPathIstioAttribute(uri)

  val targetService: TargetServiceIstioAttribute = TargetServiceIstioAttribute(findTargetService.getOrElse(unknown))

  val sourceLabel: SourceLabelIstioAttribute = SourceLabelIstioAttribute(Map(
    "app" -> unknown,
    "version" -> unknown
  ))

  val targetLabel: TargetLabelsIstioAttribute = TargetLabelsIstioAttribute(Map(
    "app" -> findTargetLabelApp.getOrElse(unknown),
    "version" -> findTargetVersion.getOrElse(unknown)
  ))

  private def findTargetService: Option[String] = {
    istioPath.map { path =>
      path.showElems(pathServiceIndex)
    }
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
}

/**
 * Resolves a valid Istio [[Path]], if any exists for the current [[DstBoundCtx]]
 */
object CurrentIstioPath {
  val log = Logger(this.getClass.getName)
  private val pathLength = 10

  def apply(): Option[Path] = {
    val path = Option(DstBoundCtx).flatMap { dstBoundCtx =>
      dstBoundCtx.current.flatMap { bound =>
        bound.id match {
          case path: Path if (path.elems.length == pathLength) => Some(path)
          case _ => None
        }
      }
    }

    if (path.isEmpty)
      log.warning("Failed resolving %s for current context %s, found %s", getClass.getSimpleName, DstBoundCtx, Option(DstBoundCtx).map(_.current))

    path
  }
}