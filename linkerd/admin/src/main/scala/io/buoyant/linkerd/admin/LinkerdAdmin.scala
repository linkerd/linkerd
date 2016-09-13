package io.buoyant.linkerd
package admin

import com.twitter.app.App
import com.twitter.finagle._
import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.server.handler.{SummaryHandler => _, _}
import com.twitter.util.Future
import io.buoyant.admin.names.{BoundNamesHandler, DelegateApiHandler, DelegateHandler}
import io.buoyant.admin.{Admin, ConfigHandler, StaticFilter}
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.Linker.LinkerConfig
import io.buoyant.namer.EnumeratingNamer
import io.buoyant.router.RoutingFactory
import io.buoyant.telemetry.Telemeter

object LinkerdAdmin {
  def boundNames(namers: Seq[Namer]): Admin.Routes = {
    val enumerating = namers.collect { case en: EnumeratingNamer => en }
    Seq("/bound-names.json" -> new BoundNamesHandler(enumerating))
  }

  def config(lc: Linker.LinkerConfig): Admin.Routes = Seq(
    "/config.json" -> new ConfigHandler(lc, Linker.LoadedInitializers.iter)
  )

  def delegator(routers: Seq[Router]): Admin.Routes = {
    val byLabel = routers.map(r => r.label -> r).toMap
    val dtabs = byLabel.mapValues { router =>
      val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
      dtab()
    }
    val interpreters = byLabel.mapValues { router =>
      val DstBindingFactory.Namer(namer) = router.params[DstBindingFactory.Namer]
      namer
    }
    def getInterpreter(label: String): NameInterpreter =
      interpreters.getOrElse(label, NameInterpreter)

    Seq(
      "/delegator" -> new DelegateHandler(AdminHandler, dtabs, getInterpreter),
      "/delegator.json" -> new DelegateApiHandler(getInterpreter)
    )
  }

  val static: Admin.Routes = Seq(
    "/" -> new DashboardHandler,
    "/files/" -> StaticFilter.andThen(ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )),
    "/help" -> new HelpPageHandler
  )

  def apply(lc: Linker.LinkerConfig, linker: Linker): Admin.Routes =
    static ++ config(lc) ++
      boundNames(linker.namers.map { case (_, n) => n }) ++
      delegator(linker.routers) ++
      linker.telemeters.flatMap(_.adminRoutes)
}
