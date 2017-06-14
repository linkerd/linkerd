package io.buoyant.interpreter.k8s

import com.twitter.finagle._
import com.twitter.finagle.buoyant.PathMatcher
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.Activity
import io.buoyant.namer.{ConfiguredDtabNamer, RewritingNamer}

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

  def apply(routeManager: Unit, istioNamer: Namer): NameInterpreter = {

    // val routes = routerManager.routes
    val routes: Activity[IndexedSeq[Unit]] = Activity.value(IndexedSeq(()))

    val routesDtab = routes.map { routeTable =>
      val dentries = routeTable.map { _ =>
        val route = "reviews-default"
        val ns = "default"
        val service = "reviews"
        val labels = "version:v1"
        Dentry.read(s"/svc/route/$route => $istioPfx/$ns/$service/$labels")
      }
      Dtab(dentries)
    }

    val noLabelsRewriteNamer = new RewritingNamer(PathMatcher("/{ns}/{svc}/{port}"), s"$istioPfx/{ns}/{svc}/::/{port}")

    val dtab = routesDtab.map(defaultRouteDtab ++ _)

    ConfiguredDtabNamer(dtab, Seq(
      Path.read(istioPfx) -> istioNamer,
      Path.read(noLabelsPfx) -> noLabelsRewriteNamer
    ))
  }
}
