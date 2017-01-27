package io.buoyant.linkerd
package admin

import com.twitter.finagle._
import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.server.handler.{ResourceHandler, SummaryHandler => _}
import io.buoyant.admin.names.{BoundNamesHandler, DelegateApiHandler, DelegateHandler}
import io.buoyant.admin.{Admin, ConfigHandler, StaticFilter, _}
import io.buoyant.namer.{Delegator, EnumeratingNamer, NamespacedInterpreterConfig}
import io.buoyant.router.RoutingFactory

object LinkerdAdmin {

  def boundNames(namers: Seq[Namer]): Admin.Handlers = {
    val enumerating = namers.collect { case en: EnumeratingNamer => en }
    Seq("/bound-names.json" -> new BoundNamesHandler(enumerating))
  }

  def config(lc: Linker.LinkerConfig): Admin.Handlers = Seq(
    "/config.json" -> new ConfigHandler(lc, Linker.LoadedInitializers.iter)
  )

  def delegator(adminHandler: AdminHandler, routers: Seq[Router]): Admin.Handlers = {
    val byLabel = routers.map(r => r.label -> r).toMap
    val dtabs = byLabel.mapValues { router =>
      val RoutingFactory.BaseDtab(dtab) = router.params[RoutingFactory.BaseDtab]
      dtab()
    }
    val interpreters = byLabel.mapValues { router =>
      val DstBindingFactory.Namer(namer) = router.params[DstBindingFactory.Namer]
      namer
    }
    println(interpreters.toString())
    def getInterpreter(label: String): NameInterpreter =
      interpreters.getOrElse(label, NameInterpreter)

    Seq(
      "/delegator" -> new DelegateHandler(adminHandler, dtabs, getInterpreter),
      "/delegator.json" -> new DelegateApiHandler(getInterpreter)
    )
  }

  def static(adminHandler: AdminHandler): Admin.Handlers = Seq(
    "/" -> new DashboardHandler(adminHandler),
    "/files/" -> StaticFilter.andThen(ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )),
    "/help" -> new HelpPageHandler(adminHandler),
    "/requests" -> new RecentRequestsPlaceholderHandler(adminHandler),
    "/logging" -> new LoggingHandler(adminHandler),
    "/logging.json" -> new LoggingApiHandler()
  )

  def apply(lc: Linker.LinkerConfig, linker: Linker): Admin.Handlers = {
    val delegatorsByLabel = linker.routers.flatMap { router =>
      val DstBindingFactory.Namer(namer) = router.params[DstBindingFactory.Namer]
      namer match {
        case delegator: Delegator => Some(router.label -> delegator)
        case _ => None
      }
    }.toMap

    val namerdInterpreterConfigs: Seq[(String, NamespacedInterpreterConfig)] = lc.routers
      .flatMap(router => router.interpreter match {
        case config: NamespacedInterpreterConfig => Some((router.label -> config))
        case _ => None
      })

    val namerdNav = namerdInterpreterConfigs.size > 0
    val adminHandler = new AdminHandler(namerdNav)

    val namerd = Seq("/namerd" -> new NamerdHandler(adminHandler, namerdInterpreterConfigs, delegatorsByLabel))
    val adminFilter = new AdminFilter(adminHandler)

    val extHandlers = Admin.extractHandlers(
      linker.namers ++
        linker.routers ++
        linker.telemeters
    ).map {
        case (path, handler) => (path, adminFilter.andThen(handler))
      }

    static(adminHandler) ++ namerd ++ config(lc) ++
      boundNames(linker.namers.map { case (_, n) => n }) ++
      delegator(adminHandler, linker.routers) ++
      extHandlers
  }
}
