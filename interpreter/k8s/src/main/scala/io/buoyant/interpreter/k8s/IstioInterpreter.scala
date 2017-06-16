package io.buoyant.interpreter.k8s

import com.twitter.finagle._
import com.twitter.finagle.buoyant.PathMatcher
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.k8s.istio.RouteManager
import io.buoyant.namer.{ConfiguredDtabNamer, RewritingNamer}
import istio.proxy.v1.config.RouteRule

object IstioInterpreter {
  private val istioPfx = "/#/io.l5d.k8s.istio"
  private val defaultRouteDtab = Dtab.read(s"""
    |/svc/ext => /$$/inet ;
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
        NameTree.Weighted(
          weightedDest.weight.getOrElse(0).toDouble,
          NameTree.Leaf(Path.read(s"/#/io.l5d.k8s.istio/$cluster/$labelSegment"))
        )
      }
      val prefix = Dentry.Prefix.read(s"/svc/route/$name")
      Some(Dentry(prefix, NameTree.Union(branches: _*)))
    }

  def apply(routeManager: RouteManager, istioNamer: Namer): NameInterpreter = {

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
      Path.read(istioPfx) -> istioNamer
    ))
  }
}
