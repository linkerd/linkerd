package io.buoyant.namerd

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Path, Namer, Stack}
import com.twitter.finagle.util.LoadService
import io.buoyant.admin.AdminConfig
import io.buoyant.config.{ConfigError, ConfigInitializer, Parser}
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

  def mk(stats: StatsReceiver): Namerd = {
    if (storage.disabled) throw new IllegalArgumentException(
      s"""The ${storage.getClass.getName} storage is experimental and must be explicitly enabled by setting the "experimental" parameter to true."""
    )
    val dtabStore = storage.mkDtabStore
    val namersByPfx = mkNamers
    Namerd(mkInterfaces(dtabStore, namersByPfx, stats), dtabStore, namersByPfx)
  }

  private[this] def mkNamers: Map[Path, Namer] =
    namers.foldLeft(Map.empty[Path, Namer]) {
      case (namers, config) =>
        if (config.prefix.isEmpty)
          throw NamerdConfig.EmptyNamerPrefix

        for (prefix <- namers.keys)
          if (prefix.startsWith(config.prefix) || config.prefix.startsWith(prefix))
            throw NamerdConfig.ConflictingNamers(prefix, config.prefix)

        namers + (config.prefix -> config.newNamer(Stack.Params.empty))
    }

  private[this] def mkInterfaces(
    dtabStore: DtabStore,
    namersByPfx: Map[Path, Namer],
    stats: StatsReceiver
  ): Seq[Servable] =
    interfaces.map(_.mk(dtabStore, namersByPfx, stats))
}

object NamerdConfig {

  case class ConflictingNamers(prefix0: Path, prefix1: Path) extends ConfigError {
    lazy val message =
      s"Namers must not have overlapping prefixes: ${prefix0.show} & ${prefix1.show}"
  }

  object EmptyNamerPrefix extends ConfigError {
    lazy val message = s"Namers must not have an empty prefix"
  }

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
