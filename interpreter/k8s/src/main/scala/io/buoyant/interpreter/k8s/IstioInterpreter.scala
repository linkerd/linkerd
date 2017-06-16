package io.buoyant.interpreter.k8s

import com.twitter.finagle._
import com.twitter.finagle.buoyant.PathMatcher
import com.twitter.finagle.naming.NameInterpreter
import io.buoyant.k8s.istio.RouteManager
import io.buoyant.namer.{ConfiguredDtabNamer, RewritingNamer}
import istio.proxy.v1.config.RouteRule

object IstioInterpreter {
  private val noLabelsPfx = "/#/no-labels"
  private val istioPfx = "/#/io.l5d.k8s.istio"
  private val defaultRouteDtab = Dtab.read(s"""
    |/svc/dest => /$$/inet ;
    |/destPath/local/cluster/svc => $noLabelsPfx ;
    |/svc/dest => /$$/io.buoyant.http.domainToPathPfx/destPath ;
  """.stripMargin)

  /* A typical delegation for the default route

   /svc/dest/foo.default.svc.cluster.local/http
   /$/io.buoyant.domainToPathPfx/destPath/foo.default.svc.cluster.local/http
   /destPath/local/cluster/svc/default/foo/http
   /#/no-labels/default/foo/http
   /#/io.l5d.k8s.istio/default/foo/::/http

   A typical delegation for a matching route
   /svc/route/my-cool-route/http
   /#/io.l5d.k8s.istio/default/reviews/version:v1/http
  */

  private[this] def mkDentry(name: String, route: RouteRule): Option[Dentry] =
    route.destination.flatMap { dest =>
      dest.split('.') match {
        case Array(svc, ns, _*) =>
          val branches = route.route.map { weightedDest =>
            val labels = weightedDest.tags.toSeq
              .sortBy(_._1)
              .map { case (k, v) => s"$k:$v" }
              .mkString("::")
            val labelSegment = if (labels.isEmpty) "::" else labels
            NameTree.Weighted(
              weightedDest.weight.getOrElse(0).toDouble,
              NameTree.Leaf(Path.read(s"/#/io.l5d.k8s.istio/$ns/$svc/$labelSegment"))
            )
          }
          val prefix = Dentry.Prefix.read(s"/svc/route/$name")
          Some(Dentry(prefix, NameTree.Union(branches: _*)))
        case _ =>
          None
      }
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

    val noLabelsRewriteNamer =
      new RewritingNamer(PathMatcher("/{ns}/{svc}/{port}"), s"$istioPfx/{ns}/{svc}/::/{port}")

    val dtab = routesDtab.map(defaultRouteDtab ++ _)

    ConfiguredDtabNamer(dtab, Seq(
      Path.read(istioPfx) -> istioNamer,
      Path.read(noLabelsPfx) -> noLabelsRewriteNamer
    ))
  }
}
