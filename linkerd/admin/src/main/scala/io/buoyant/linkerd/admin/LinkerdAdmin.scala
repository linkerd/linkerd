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
import io.buoyant.namer.EnumeratingNamer
import io.buoyant.router.RoutingFactory

class LinkerdAdmin(
  app: App,
  linker: Linker,
  config: Linker.LinkerConfig
) extends Admin[Linker.LinkerConfig] {

  private[this] val dtabsByRouter: Map[String, Dtab] =
    linker.routers.map { router =>
      val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
      router.label -> dtab()
    }.toMap

  private[this] val enumeratingNamers: Seq[EnumeratingNamer] =
    linker.namers.collect { case (_, namer: EnumeratingNamer) => namer }

  private[this] val interpretersByRouter: Map[String, NameInterpreter] =
    linker.routers.map { router =>
      val DstBindingFactory.Namer(namer) = router.params[DstBindingFactory.Namer]
      router.label -> namer
    }.toMap

  private[this] def interpreterForRouter(label: String): NameInterpreter =
    linker.getOrElse(label, NameInterpreter)

  private[this] val linkerdAdminRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )),
    "/delegator" -> new DelegateHandler(AdminHandler, dtabs, interpreterForRouter),
    "/delegator.json" -> new DelegateApiHandler(interpreterForRouter),
    "/help" -> new HelpPageHandler,
    "/config.json" -> new ConfigHandler(config, Linker.LoadedInitializers.iter),
    "/bound-names.json" -> new BoundNamesHandler(enumeratingNamers)
  )

  override protected[this] val baseRoutes =
    super.baseRoutes ++ linkerdAdminRoutes
}
