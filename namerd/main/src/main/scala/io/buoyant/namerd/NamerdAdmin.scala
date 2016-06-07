package io.buoyant.namerd

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Response, Request}
import com.twitter.server.handler.ResourceHandler
import io.buoyant.admin.names.BoundNamesHandler
import io.buoyant.admin.{StaticFilter, ConfigHandler, Admin, App}
import io.buoyant.linkerd.admin.names.DelegateApiHandler
import io.buoyant.namer.EnumeratingNamer

class NamerdAdmin(app: App, config: NamerdConfig, namerd: Namerd) extends Admin(app) {

  override def allRoutes: Seq[(String, Service[Request, Response])] = {
    super.allRoutes ++ namerdAdminRoutes
  }

  private[this] def namerdAdminRoutes: Seq[(String, Service[Request, Response])] = Seq(
    "/config.json" -> new ConfigHandler(config, NamerdConfig.LoadedInitializers.iter),
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )),
    "/" -> new DtabListHandler(namerd.dtabStore),
    "/delegator.json" -> new DelegateApiHandler(namerd.namers.toSeq),
    "/dtab/" -> new DtabHandler(namerd.dtabStore)
  )
}
