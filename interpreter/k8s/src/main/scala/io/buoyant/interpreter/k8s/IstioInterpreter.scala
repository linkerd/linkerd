package io.buoyant.interpreter.k8s

import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.k8s.SingleNsNamer
import io.buoyant.k8s.istio.RouteCache
import io.buoyant.namer.ConfiguredDtabNamer
import istio.proxy.v1.config.RouteRule

object IstioInterpreter {
  private val istioPfx = "/#/io.l5d.k8s.istio"
  private val k8sPfx = "/#/io.l5d.k8s.ns"
  private val defaultRouteDtab = Dtab.read(s"""
    |/egress => $k8sPfx/incoming/istio-egress ;
    |/svc/ext => /egress ;
    |/svc/dest => /egress ;
    |/svc/dest => $istioPfx ;
  """.stripMargin)

  /* A typical delegation for the default route
   /svc/dest/reviews.default.svc.cluster.local/::/http
   /#/io.l5d.k8s.istio/reviews.default.svc.cluster.local/::/http

   A typical delegation for a matching route
   /svc/route/my-cool-route/http
   /#/io.l5d.k8s.istio/reviews.default.svc.cluster.local/version:v1/http

   A typical delegation for no matching cluster
   /svc/ext/google.com/80
   /$/inet/google.com/80
  */

  private[this] def mkDentry(name: String, route: RouteRule): Option[Dentry] =
    route.destination.flatMap { cluster =>
      val branches = route.route.map { weightedDest =>
        val labels = weightedDest.tags.toSeq
          .sortBy(_._1)
          .map { case (k, v) => s"$k:$v" }
          .mkString("::")
        val labelSegment = if (labels.isEmpty) "::" else labels
        val clusterSegment = weightedDest.`destination`.getOrElse(cluster)
        NameTree.Weighted(
          weightedDest.weight.getOrElse(0).toDouble,
          NameTree.Leaf(Path.read(s"/#/io.l5d.k8s.istio/$clusterSegment/$labelSegment"))
        )
      }
      val dst = if (branches.isEmpty)
        NameTree.Leaf(Path.read(s"/#/io.l5d.k8s.istio/$cluster/::"))
      else
        NameTree.Union(branches: _*)
      val prefix = Dentry.Prefix.read(s"/svc/route/$name")
      Some(Dentry(prefix, dst))
    }

  def apply(routeManager: RouteCache, istioNamer: Namer, k8sNamer: SingleNsNamer): NameInterpreter = {

    val routes = routeManager.routeRules

    val routesDtab = routes.map { routeTable =>
      val dentries = routeTable.toIndexedSeq.flatMap {
        case (name, route) =>
          mkDentry(name, route)
      }
      Dtab(dentries)
    }

    val dtab = routesDtab.map(defaultRouteDtab ++ _)

    ConfiguredDtabNamer(dtab, Seq(
      Path.read(istioPfx) -> istioNamer,
      Path.read(k8sPfx) -> k8sNamer
    ))
  }
}
