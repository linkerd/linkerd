package io.buoyant.linkerd.admin

import com.twitter.app.App
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.server.handler.{SummaryHandler => _, _}
import com.twitter.util.Future
import io.buoyant.admin.names.DelegateHandler
import io.buoyant.admin.{StaticFilter, ConfigHandler, Admin}
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.Linker.LinkerConfig
import io.buoyant.linkerd.admin.names.DelegateApiHandler
import io.buoyant.router.RoutingFactory

class LinkerdAdmin(app: App, linker: Linker, config: LinkerConfig) extends Admin(app) {

  private[this] val dtabs: Future[Map[String, Dtab]] =
    Future.value(
      linker.routers.map { router =>
      val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
      router.label -> dtab()
    }.toMap
    )

  private[this] def linkerdAdminRoutes: Seq[(String, Service[Request, Response])] = Seq(

    "/" -> new DashboardHandler,
    "/legacy-dashboard" -> new SummaryHandler(linker),
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )),
    "/delegator" -> new DelegateHandler(AdminHandler, () => dtabs, linker.namers),
    "/delegator.json" -> new DelegateApiHandler(linker.namers),
    "/dtab/" -> new DtabHandler(() => dtabs),
    "/metrics" -> MetricsHandler,
    "/config.json" -> new ConfigHandler(config, Linker.LoadedInitializers.iter)
  )

  override def allRoutes = super.allRoutes ++ linkerdAdminRoutes
}
