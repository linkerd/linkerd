package io.buoyant.namerd

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.admin.AdminConfig
import io.buoyant.config.{ConfigInitializer, Parser}
import io.buoyant.namer.{NamerConfig, NamerInitializer}

case class NamerdConfig(
  admin: Option[AdminConfig],
  storage: DtabStoreConfig,
  namers: Seq[NamerConfig],
  interfaces: Seq[InterfaceConfig]
) {
  // TODO: remove the null checks once we upgrade to a Jackson supporting FAIL_ON_MISSING_CREATOR_PROPERTIES
  require(namers != null, "'namers' field is required")
  require(interfaces != null, "'interfaces' field is required")
  require(interfaces.nonEmpty, "One or more interfaces must be specified")

  def mk: Namerd = {
    val dtabStore = storage.mkDtabStore
    Namerd(mkInterfaces(dtabStore), dtabStore)
  }

  private[this] def mkInterfaces(dtabStore: DtabStore): Seq[Servable] = {
    val namersByPfx = namers.map { config =>
      config.prefix -> config.newNamer(Stack.Params.empty)
    }

    // TODO: validate the absence of port conflicts
    interfaces.map(_.mk(dtabStore, namersByPfx))
  }
}

object NamerdConfig {

  private[namerd] case class Initializers(
    namer: Seq[NamerInitializer] = Nil,
    dtabStore: Seq[DtabStoreInitializer] = Nil,
    iface: Seq[InterfaceInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(namer, dtabStore, iface)
  }

  private[namerd] lazy val LoadedInitializers = Initializers(
    LoadService[NamerInitializer],
    LoadService[DtabStoreInitializer],
    LoadService[InterfaceInitializer]
  )

  def loadNamerd(configText: String, initializers: Initializers): NamerdConfig = {
    val mapper = Parser.objectMapper(configText, initializers.iter)
    mapper.readValue[NamerdConfig](configText)
  }

  def loadNamerd(configText: String): NamerdConfig =
    loadNamerd(configText, LoadedInitializers)
}
