package io.buoyant.linkerd.admin

import com.twitter.app.App
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.server.handler.{SummaryHandler => _, _}
import io.buoyant.admin.Admin
import io.buoyant.linkerd.Linker
import io.buoyant.linkerd.Linker.LinkerConfig

class LinkerdAdmin(app: App, linker: Linker, config: LinkerConfig) extends Admin(app) {

  private[this] def linkerdAdminRoutes: Seq[(String, Service[Request, Response])] = Seq(

    "/" -> new SummaryHandler(linker),
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/linkerd/admin",
      localFilePath = "linkerd/admin/src/main/resources/io/buoyant/linkerd/admin"
    )),
    "/delegator" -> DelegateHandler.ui(linker),
    "/delegator.json" -> DelegateHandler.api(linker),
    "/routers.json" -> new RouterHandler(linker),
    "/metrics" -> MetricsHandler,
    "/config.json" -> new ConfigHandler(config, Linker.LoadedInitializers.iter)
  )

  override def allRoutes = super.allRoutes ++ linkerdAdminRoutes
}
