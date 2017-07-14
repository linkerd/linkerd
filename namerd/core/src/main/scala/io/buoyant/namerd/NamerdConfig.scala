package io.buoyant.namerd

import com.twitter.finagle.stats.{BroadcastStatsReceiver, LoadedStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.util.{DefaultTimer, LoadService}
import com.twitter.finagle.{Namer, Path, Stack, param}
import com.twitter.logging.Logger
import com.twitter.server.util.JvmStats
import io.buoyant.admin.AdminConfig
import io.buoyant.config.{ConfigError, ConfigInitializer, Parser}
import io.buoyant.namer.{NamerConfig, NamerInitializer, TransformerInitializer}
import io.buoyant.telemetry._
import io.buoyant.telemetry.admin.{AdminMetricsExportTelemeter, histogramSnapshotInterval}
import java.net.InetSocketAddress
import scala.util.control.NoStackTrace

private[namerd] case class NamerdConfig(
  admin: Option[AdminConfig],
  storage: DtabStoreConfig,
  namers: Option[Seq[NamerConfig]],
  interfaces: Seq[InterfaceConfig],
  telemetry: Option[Seq[TelemeterConfig]]
) {
  require(interfaces != null, "'interfaces' field is required")
  require(interfaces.nonEmpty, "One or more interfaces must be specified")
  import NamerdConfig._

  def mk(): Namerd = {
    if (storage.disabled) {
      val msg = s"The ${storage.getClass.getName} storage is experimental and must be " +
        "explicitly enabled by setting the `experimental' parameter to true."
      throw new IllegalArgumentException(msg) with NoStackTrace
    }

    val metrics = MetricsTree()

    val telemeterParams = Stack.Params.empty + metrics
    val adminTelemeter = new AdminMetricsExportTelemeter(metrics, histogramSnapshotInterval(), DefaultTimer)
    val telemeters = telemetry.toSeq.flatten.map {
      case t if t.disabled =>
        val msg = s"The ${t.getClass.getCanonicalName} telemeter is experimental and must be " +
          "explicitly enabled by setting the `experimental' parameter to `true'."
        throw new IllegalArgumentException(msg) with NoStackTrace
      case t =>
        val telem = t.mk(telemeterParams)
        telem.tracer match {
          case s: NullTracer =>
          case _ =>
            log.warning(s"Telemeter ${t.getClass.getCanonicalName} defines a tracer but namerd doesn't support tracing")
        }
        telem
    } :+ adminTelemeter

    // Telemeters may provide StatsReceivers.
    val stats = mkStats(metrics, telemeters)
    JvmStats.register(stats)
    LoadedStatsReceiver.self = NullStatsReceiver

    val dtabStore = storage.mkDtabStore
    val namersByPfx = mkNamers(Stack.Params.empty + param.Stats(stats.scope("namer")))
    val ifaces = mkInterfaces(dtabStore, namersByPfx, stats.scope("interface"))
    val adminImpl = admin.getOrElse(DefaultAdminConfig).mk(DefaultAdminAddress)

    new Namerd(ifaces, dtabStore, namersByPfx, adminImpl, telemeters)
  }

  private[this] def mkStats(metrics: MetricsTree, telemeters: Seq[Telemeter]) = {
    val receivers = telemeters.collect { case t if !t.stats.isNull => t.stats } :+ new MetricsTreeStatsReceiver(metrics)
    for (r <- receivers) log.debug("stats: %s", r)
    BroadcastStatsReceiver(receivers)
  }

  private[this] def mkNamers(params: Stack.Params): Map[Path, Namer] =
    namers.getOrElse(Nil).foldLeft(Map.empty[Path, Namer]) {
      case (namers, config) =>
        if (config.prefix.isEmpty)
          throw NamerdConfig.EmptyNamerPrefix

        for (prefix <- namers.keys)
          if (prefix.startsWith(config.prefix) || config.prefix.startsWith(prefix))
            throw NamerdConfig.ConflictingNamers(prefix, config.prefix)

        namers + (config.prefix -> config.mk(params))
    }

  private[this] def mkInterfaces(
    dtabStore: DtabStore,
    namersByPfx: Map[Path, Namer],
    stats: StatsReceiver
  ): Seq[Servable] =
    interfaces.map(_.mk(dtabStore, namersByPfx, stats))
}

private[namerd] object NamerdConfig {
  private val log = Logger()

  private def DefaultAdminAddress = new InetSocketAddress(9991)
  private def DefaultAdminConfig = AdminConfig()

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
    iface: Seq[InterfaceInitializer] = Nil,
    transformers: Seq[TransformerInitializer] = Nil,
    telemeters: Seq[TelemeterInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(namer, dtabStore, iface, transformers, telemeters)
  }

  private[namerd] lazy val LoadedInitializers = Initializers(
    LoadService[NamerInitializer],
    LoadService[DtabStoreInitializer],
    LoadService[InterfaceInitializer],
    LoadService[TransformerInitializer],
    LoadService[TelemeterInitializer]
  )

  def loadNamerd(configText: String, initializers: Initializers): NamerdConfig = {
    val mapper = Parser.objectMapper(configText, initializers.iter)
    mapper.readValue[NamerdConfig](configText)
  }

  def loadNamerd(configText: String): NamerdConfig =
    loadNamerd(configText, LoadedInitializers)
}
