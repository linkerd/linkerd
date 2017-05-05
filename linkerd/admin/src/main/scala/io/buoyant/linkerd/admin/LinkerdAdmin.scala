package io.buoyant.linkerd
package admin

import com.twitter.finagle._
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.server.handler.ResourceHandler
import io.buoyant.admin.Admin.{Handler, NavItem}
import io.buoyant.admin.names.{BoundNamesHandler, DelegateApiHandler, DelegateHandler}
import io.buoyant.admin.{Admin, ConfigHandler, StaticFilter, _}
import io.buoyant.namer.EnumeratingNamer
import io.buoyant.router.RoutingFactory

object LinkerdAdmin {

  def boundNames(namers: Seq[Namer]): Seq[Handler] = {
    val enumerating = namers.collect { case en: EnumeratingNamer => en }
    Seq(Handler("/bound-names.json", new BoundNamesHandler(enumerating)))
  }

  def config(lc: Linker.LinkerConfig): Seq[Handler] = Seq(
    Handler("/config.json", new ConfigHandler(lc, Linker.LoadedInitializers.iter))
  )

  def delegator(adminHandler: AdminHandler, routers: Seq[Router]): Seq[Handler] = {
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
      Handler("/delegator", new DelegateHandler(adminHandler, dtabs, getInterpreter)),
      Handler("/delegator.json", new DelegateApiHandler(getInterpreter))
    )
  }

  def static(adminHandler: AdminHandler): Seq[Handler] = Seq(
    Handler("/", new DashboardHandler(adminHandler)),
    Handler("/files/", StaticFilter.andThen(ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    ))),
    Handler("/help", new HelpPageHandler(adminHandler)),
    Handler("/logging", new LoggingHandler(adminHandler)),
    Handler("/logging.json", new LoggingApiHandler())
  )

  def apply(lc: Linker.LinkerConfig, linker: Linker): Seq[Handler] = {
    val navItems = Seq(
      NavItem("dtab", "delegator"),
      NavItem("logging", "logging")
    ) ++ Admin.extractNavItems(
        linker.namers ++
          linker.routers.map(_.interpreter) ++
          linker.routers ++
          linker.telemeters
      ) :+ NavItem("help", "help")

    def uniqBy[T, U](items: Seq[T])(f: T => U): Seq[T] = items match {
      case Nil => items
      case Seq(t) => items
      case t +: rest if rest.map(f).contains(f(t)) => uniqBy(rest)(f)
      case t +: rest => t +: uniqBy(rest)(f)
    }

    val adminHandler = new AdminHandler(uniqBy(navItems)(_.name))

    val extHandlers = Admin.extractHandlers(
      linker.namers ++
        linker.routers ++
        linker.routers.map(_.interpreter) ++
        linker.telemeters
    ).map {
        case Handler(url, service, css) =>
          val adminFilter = new AdminFilter(adminHandler, css)
          Handler(url, adminFilter.andThen(service), css)
      }

    static(adminHandler) ++ config(lc) ++
      boundNames(linker.namers.map { case (_, n) => n }) ++
      delegator(adminHandler, linker.routers) ++
      extHandlers
  }
}
