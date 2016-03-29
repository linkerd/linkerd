package io.buoyant.namerd

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Response, Request}
import io.buoyant.admin.{ConfigHandler, Admin, App}

class NamerdAdmin(app: App, config: NamerdConfig) extends Admin(app) {
  override def allRoutes: Seq[(String, Service[Request, Response])] =
    super.allRoutes :+
      ("/config.json" -> new ConfigHandler(config, NamerdConfig.LoadedInitializers.iter))
}
