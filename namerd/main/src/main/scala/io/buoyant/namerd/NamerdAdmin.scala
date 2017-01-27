package io.buoyant.namerd

import com.twitter.finagle.{Namer, Path}
import com.twitter.server.handler.ResourceHandler
import io.buoyant.admin.Admin.Handler
import io.buoyant.admin.names.DelegateApiHandler
import io.buoyant.admin.{Admin, ConfigHandler, StaticFilter}
import io.buoyant.namer.ConfiguredNamersInterpreter

object NamerdAdmin {

  val static: Seq[Handler] = Seq(
    Handler("/files/", (StaticFilter andThen ResourceHandler.fromDirectoryOrJar(
      baseRequestPath = "/files/",
      baseResourcePath = "io/buoyant/admin",
      localFilePath = "admin/src/main/resources/io/buoyant/admin"
    )))
  )

  def config(nc: NamerdConfig) = Seq(
    Handler("/config.json", new ConfigHandler(nc, NamerdConfig.LoadedInitializers.iter))
  )

  def dtabs(dtabStore: DtabStore, namers: Map[Path, Namer]) = Seq(
    Handler("/", new DtabListHandler(dtabStore)),
    Handler("/dtab/delegator.json", new DelegateApiHandler(ns => ConfiguredNamersInterpreter(namers.toSeq))),
    Handler("/dtab/", new DtabHandler(dtabStore))
  )

  def apply(nc: NamerdConfig, namerd: Namerd): Seq[Handler] =
    static ++ config(nc) ++
      dtabs(namerd.dtabStore, namerd.namers) ++
      Admin.extractHandlers(namerd.dtabStore +: (namerd.namers.values.toSeq ++ namerd.telemeters))

}
