package io.buoyant.linkerd
package admin

import com.twitter.finagle._
import com.twitter.finagle.client.buoyant.ClientStateHandler
import com.twitter.finagle.http.Request
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.server.handler.ResourceHandler
import io.buoyant.admin.Admin.{Handler, NavItem}
import io.buoyant.admin.names.{BoundNamesHandler, DelegateApiHandler, DelegateHandler}
import io.buoyant.admin.{Admin, ConfigHandler, StaticFilter, _}
import io.buoyant.namer.EnumeratingNamer
import io.buoyant.router.{Http, RoutingFactory}

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

  val clientState: Handler = Handler("/client_state.json", new ClientStateHandler)

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

  def identifierHandler(lc: Linker.LinkerConfig, linker: Linker): Handler = {
    linker.routers.head.protocol.configId

    val identifiers = linker.routers.collect {
      case router if router.protocol.configId == "http" =>
        val RoutingFactory.DstPrefix(pfx) = router.params[RoutingFactory.DstPrefix]
        val RoutingFactory.BaseDtab(baseDtab) = router.params[RoutingFactory.BaseDtab]
        val Http.param.HttpIdentifier(id) = router.params[Http.param.HttpIdentifier]
        router.label -> id(pfx, baseDtab)
    }.toMap

    Handler("/", new HttpIdentifierHandler(identifiers))
  }

  def extractInterpreterNavItems(routers: Seq[Router]): Seq[Admin.NavItem] = {
    routers.flatMap { router =>
      router.interpreter match {
        case withNav: Admin.WithNavItems =>
          withNav.navItems.map { item =>
            item.copy(url = s"/${router.label}${item.url}")
          }
        case _ => Nil
      }
    }
  }

  def extractInterpreterHandlers(routers: Seq[Router]): Seq[Admin.Handler] = {
    routers.flatMap { router =>
      router.interpreter match {
        case withHandlers: Admin.WithHandlers =>
          withHandlers.adminHandlers.map { handler =>
            handler.copy(url = s"/${router.label}${handler.url}")
          }
        case _ =>
          Nil
      }
    }
  }

  def apply(lc: Linker.LinkerConfig, linker: Linker): Seq[Handler] = {
    val navItems = Seq(
      NavItem("dtab", "delegator"),
      NavItem("logging", "logging")
    ) ++ Admin.extractNavItems(
        linker.namers.map(_._2) ++
          linker.routers ++
          linker.telemeters
      ) ++ extractInterpreterNavItems(linker.routers) :+ NavItem("help", "help")

    def uniqBy[T, U](items: Seq[T])(f: T => U): Seq[T] = items match {
      case Nil => items
      case Seq(t) => items
      case t +: rest if rest.map(f).contains(f(t)) => uniqBy(rest)(f)
      case t +: rest => t +: uniqBy(rest)(f)
    }

    val adminHandler = new AdminHandler(uniqBy(navItems)(_.name))

    val extHandlers = (Admin.extractHandlers(
      linker.namers.map(_._2) ++
        linker.routers ++
        linker.telemeters
    ) ++ extractInterpreterHandlers(linker.routers))
      .map {
        case Handler(url, service, css) =>
          val adminFilter = new AdminFilter(adminHandler, css)
          Handler(url, adminFilter.andThen(service), css)
      }

    static(adminHandler) ++ config(lc) ++
      boundNames(linker.namers.map { case (_, n) => n }) ++
      delegator(adminHandler, linker.routers) ++
      extHandlers :+ clientState
  }
}
