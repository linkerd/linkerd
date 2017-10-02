package io.buoyant.k8s.istio

import com.twitter.finagle.Path
import com.twitter.finagle.buoyant.Dst
import com.twitter.logging.Logger
import io.buoyant.router.context.DstBoundCtx

object CurrentIstioPath {
  val log = Logger(this.getClass.getName)
  private val pathLength = 10

  def apply(maybeBound: Option[Dst.Bound]): Option[Path] = {
    maybeBound.flatMap { bound =>
      bound.id match {
        case path: Path if (path.elems.length == pathLength) => Some(path)
        case _ => None
      }
    }
  }

  /**
   * Resolves a valid Istio [[Path]], if any exists for the current [[DstBoundCtx]]
   */
  def apply(): Option[Path] = {
    Option(DstBoundCtx) match {
      case Some(p) => apply(p.current)
      case None =>
        log.warning(
          "Couldn' find a bound %s for current context %s, found %s",
          getClass.getSimpleName,
          DstBoundCtx,
          Option(DstBoundCtx).map(_.current)
        )
        None
    }
  }
}
