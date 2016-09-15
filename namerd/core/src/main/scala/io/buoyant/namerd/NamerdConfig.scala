package io.buoyant.namerd

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Path, Namer, Stack}
import com.twitter.finagle.stats.LoadedStatsReceiver
import com.twitter.finagle.util.LoadService
import io.buoyant.admin.AdminConfig
import io.buoyant.config.{ConfigError, ConfigInitializer, Parser}
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.telemetry.{Telemeter, NullTelemeter}
import scala.util.control.NoStackTrace

private[namerd] case class NamerdConfig(
  admin: Option[AdminConfig],
  storage: DtabStoreConfig,
  namers: Seq[NamerConfig],
  interfaces: Seq[InterfaceConfig]
) {
  require(namers != null, "'namers' field is required")
  require(interfaces != null, "'interfaces' field is required")
  require(interfaces.nonEmpty, "One or more interfaces must be specified")
  import NamerdConfig._

  def mk(defaultTelemeter: Telemeter = NullTelemeter): Namerd = {
    if (storage.disabled) {
      val msg = s"The ${storage.getClass.getName} storage is experimental and must be " +
        "explicitly enabled by setting the `experimental' parameter to true."
      throw new IllegalArgumentException(msg) with NoStackTrace
    }

    val stats = defaultTelemeter.stats
    LoadedStatsReceiver.self = stats

    val dtabStore = storage.mkDtabStore
    val namersByPfx = mkNamers()
    val ifaces = mkInterfaces(dtabStore, namersByPfx, stats)
    val adminImpl = admin.getOrElse(DefaultAdminConfig).mk(DefaultAdminPort)
    val telemeters = Seq(defaultTelemeter)
    new Namerd(ifaces, dtabStore, namersByPfx, adminImpl, telemeters)
  }

  private[this] def mkNamers(): Map[Path, Namer] =
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

private[namerd] object NamerdConfig {

  private def DefaultAdminPort = Port(9991)
  private def DefaultAdminConfig = AdminConfig(Some(DefaultAdminPort))

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
