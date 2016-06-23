package io.buoyant.linkerd.admin

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

class LinkerdAdmin(app: App, linker: Linker, config: LinkerConfig) extends Admin(app) {

  private[this] val dtabs: Map[String, Dtab] =
    linker.routers.map { router =>
      val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
      router.label -> dtab()
    }.toMap

  private[this] val enumeratingNamers = linker.namers.collect {
    case (_, namer: EnumeratingNamer) => namer
  }

  private[this] def linkerdAdminRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/" -> new DashboardHandler,
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )),
    "/delegator" -> new DelegateHandler(AdminHandler, dtabs, interpreterForRouter),
    "/delegator.json" -> new DelegateApiHandler(interpreterForRouter),
    "/metrics" -> MetricsHandler,
    "/help" -> new HelpPageHandler,
    "/config.json" -> new ConfigHandler(config, Linker.LoadedInitializers.iter),
    "/bound-names.json" -> new BoundNamesHandler(enumeratingNamers)
  )

  private[this] def interpreterForRouter(label: String): NameInterpreter =
    linker.routers.find(_.label == label).map { router =>
      router.params[DstBindingFactory.Namer].interpreter
    }.getOrElse(NameInterpreter)

  override def allRoutes = super.allRoutes ++ linkerdAdminRoutes
}
