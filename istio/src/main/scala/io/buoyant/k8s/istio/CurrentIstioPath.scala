package io.buoyant.k8s.istio

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.logging.Logger
import io.buoyant.router.context.DstBoundCtx

object CurrentIstioPath {
  val logger = Logger(this.getClass.getName)

  def apply(maybeBound: Option[Dst.Bound]): Option[Path] = {
    maybeBound.flatMap { bound =>
      bound.id match {
        case path: Path => Some(path)
        case other =>
          logger.warning("Expected path, got %s", other)
          None
      }
    }
  }

  /**
   * Returns the target service requested based on the supplied `Path`.
   *
   * The service is expected to be specified in the third last element in the path. For example,
   * in the path:
   *
   * `%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http`
   *
   * The service is `reviews.default.svc.cluster.local`.
   *
   *
   * @param istioPath
   * @return
   */
  def targetServiceIn(istioPath: Path): Option[String] = {
    val pathServiceIndex = istioPath.elems.size - 3
    if (pathServiceIndex > 0) {
      Some(istioPath.showElems(pathServiceIndex))
    } else {
      logger.warning(
        "Tried parsing target service at index %s for Invalid Istio Path %s",
        pathServiceIndex,
        istioPath
      )
      None
    }
  }

  /**
   *
   * Returns the target version of the app requested based on the supplied `Path`.
   *
   * The version of the app is expected to be specified in the second last element in the path. For
   * example, in the path:
   *
   * `%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http`
   *
   * The version is contained in the segment `az:us-west::env:prod::version:v1`, and its value is `v1`.
   *
   * @param istioPath
   * @return
   */
  def targetVersionIn(istioPath: Path): Option[String] = {
    val pathLabelsIndex = istioPath.elems.size - 2
    if (pathLabelsIndex >= 0) {
      istioPath
        .showElems(pathLabelsIndex).split("::")
        .find { e => e.startsWith("version:") }
        .map { label => label.split(":").last }
    } else {
      logger.warning(
        "Tried parsing target version at index %s for Invalid Istio Path %s",
        pathLabelsIndex,
        istioPath
      )
      None
    }
  }

  /**
   * Returns the app requested based on the supplied `Path`.
   *
   * The app is expected to be the first segment of the service as returned by `targetServiceIn()`.
   * For example, in the path:
   *
   * `%,io.l5d.k8s.daemonset,default,incoming,l5d,#,io.l5d.k8s.istio,reviews.default.svc.cluster.local,az:us-west::env:prod::version:v1,http`
   *
   * The service is `reviews.default.svc.cluster.local`, and the app is `reviews`.
   *
   * Note that this should really come from the "app" label of the target pod. in most cases this
   * will be the same value, but eventually the correct solution should be to get this directly from
   * the label or the uid.
   *
   * @param istioPath
   * @return
   */
  def targetAppIn(istioPath: Path): Option[String] = targetServiceIn(istioPath)
    .flatMap(_.split('.').headOption)

  /**
   * Resolves a valid Istio `Path`, if any exists for the current `DstBoundCtx`
   */
  def apply(): Option[Path] = {
    Option(DstBoundCtx) match {
      case Some(p) => apply(p.current)
      case None =>
        logger.warning(
          "Couldn' find a bound %s for current context %s, found %s",
          getClass.getSimpleName,
          DstBoundCtx,
          Option(DstBoundCtx).map(_.current)
        )
        None
    }
  }
}
