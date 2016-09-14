package io.buoyant.namerd

import com.twitter.finagle.{Path, Namer, Service}
import com.twitter.finagle.http.{Request, Response}
import com.twitter.server.handler.ResourceHandler
import io.buoyant.admin.{Admin, App, ConfigHandler, StaticFilter}
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.namer.ConfiguredNamersInterpreter

object NamerdAdmin {

  val static: Admin.Handlers = Seq(
    "/files/" -> (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    ))
  )

  def config(nc: NamerdConfig): Admin.Handlers = Seq(
    "/config.json" -> new ConfigHandler(nc, NamerdConfig.LoadedInitializers.iter)
  )

  def dtabs(dtabStore: DtabStore, namers: Map[Path, Namer]): Admin.Handlers = Seq(
    "/" -> new DtabListHandler(dtabStore),
    "/delegator.json" -> new DelegateApiHandler(ns => ConfiguredNamersInterpreter(namers.toSeq)),
    "/dtab/" -> new DtabHandler(dtabStore)
  )

  def apply(nc: NamerdConfig, namerd: Namerd): Admin.Handlers =
    static ++ config(nc) ++
      dtabs(namerd.dtabStore, namerd.namers) ++
      Admin.extractHandlers(namerd.dtabStore +: namerd.namers.values.toSeq)
}
