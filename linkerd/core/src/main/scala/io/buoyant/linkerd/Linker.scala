package io.buoyant.linkerd

import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{param, Path, Namer, Stack}
import com.twitter.finagle.stats.{BroadcastStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{NullTracer, BroadcastTracer, Tracer}
import com.twitter.finagle.util.LoadService
import com.twitter.logging.Logger
import io.buoyant.admin.AdminConfig
import io.buoyant.config._
import io.buoyant.namer.Param.Namers
import io.buoyant.namer._
import io.buoyant.telemetry.{DefaultTelemeter, TelemeterInitializer, TelemeterConfig, Telemeter}

/**
 * Represents the total configuration of a Linkerd process.
 */
trait Linker {
  def routers: Seq[Router]
  def namers: Seq[(Path, Namer)]
  def admin: AdminConfig
  def tracer: Tracer
  def telemeters: Seq[Telemeter]
  def configured[T: Stack.Param](t: T): Linker
}

object Linker {
  private[this] val log = Logger()

  private[linkerd] case class Initializers(
    protocol: Seq[ProtocolInitializer] = Nil,
    namer: Seq[NamerInitializer] = Nil,
    interpreter: Seq[InterpreterInitializer] = Nil,
    transformer: Seq[TransformerInitializer] = Nil,
    tlsClient: Seq[TlsClientInitializer] = Nil,
    tracer: Seq[TracerInitializer] = Nil,
    identifier: Seq[IdentifierInitializer] = Nil,
    classifier: Seq[ResponseClassifierInitializer] = Nil,
    telemetry: Seq[TelemeterInitializer] = Nil,
    announcer: Seq[AnnouncerInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(protocol, namer, interpreter, tlsClient, tracer, identifier, transformer, classifier, telemetry, announcer)

    def all: Seq[ConfigInitializer] = iter.flatten.toSeq

    def parse(config: String): LinkerConfig =
      Linker.parse(config, this)

    def load(config: String): Linker =
      Linker.load(config, this)
  }

  private[linkerd] lazy val LoadedInitializers = Initializers(
    LoadService[ProtocolInitializer],
    LoadService[NamerInitializer],
    LoadService[InterpreterInitializer] :+ DefaultInterpreterInitializer,
    LoadService[TransformerInitializer],
    LoadService[TlsClientInitializer],
    LoadService[TracerInitializer],
    LoadService[IdentifierInitializer],
    LoadService[ResponseClassifierInitializer],
    LoadService[TelemeterInitializer],
    LoadService[AnnouncerInitializer]
  )

  def parse(
    config: String,
    inits: Initializers = LoadedInitializers
  ): LinkerConfig = {
    val mapper = Parser.objectMapper(config, inits.iter)
    mapper.readValue[LinkerConfig](config)
  }

  private[linkerd] def load(config: String, inits: Initializers): Linker =
    parse(config, inits).mk()

  def load(config: String): Linker =
    load(config, LoadedInitializers)

  case class LinkerConfig(
    namers: Option[Seq[NamerConfig]],
    routers: Seq[RouterConfig],
    tracers: Option[Seq[TracerConfig]],
    telemetry: Option[Seq[TelemeterConfig]],
    admin: Option[AdminConfig]
  ) {
    def mk(): Linker = {
      // At least one router must be specified
      if (routers.isEmpty) throw NoRoutersSpecified

      val telemeters = telemetry match {
        case None =>
          // Use the default stats receiver but require explicit
          // tracer configuration.
          val default = new DefaultTelemeter(true, false)
          Seq(default)
        case Some(telemeters) =>
          telemeters.map(_.mk(Stack.Params.empty))
      }

      // Telemeters may provide StatsReceivers.  Note that if all
      // telemeters provide implementations that do not use the
      // default Metrics registry, linker stats may be missing from
      // /admin/metrics.json
      val stats = {
        val receivers = telemeters.collect { case t if !t.stats.isNull => t.stats }
        for (r <- receivers) log.info("stats: %s", r)
        BroadcastStatsReceiver(receivers)
      }

      // Similarly, tracers may be provided by telemeters OR by
      // 'tracers' configuration.
      //
      // TODO the TracerInitializer API should be killed and these
      // modules should be converted to Telemeters.
      val tracer = {
        val all = tracers.getOrElse(Nil).map(_.newTracer()) ++
          telemeters.collect { case t if !t.tracer.isNull => t.tracer }
        for (t <- all) log.info("tracer: %s", t)
        BroadcastTracer(all)
      }

      val baseParams = Stack.Params.empty + param.Tracer(tracer) + param.Stats(stats)
      val namerParams = baseParams + param.Stats(stats.scope("namer"))
      val namersByPrefix = namers.getOrElse(Nil).reverse.map { namer =>
        if (namer.disabled) throw new IllegalArgumentException(
          s"""The ${namer.prefix.show} namer is experimental and must be explicitly enabled by setting the "experimental" parameter to true."""
        )
        namer.prefix -> namer.newNamer(namerParams)
      }

      NameInterpreter.setGlobal(ConfiguredNamersInterpreter(namersByPrefix))

      // Router labels must not conflict
      for ((label, rts) <- routers.groupBy(_.label))
        if (rts.size > 1) throw ConflictingLabels(label)

      val routerParams = baseParams +
        Namers(namersByPrefix) +
        param.Stats(baseParams[param.Stats].statsReceiver.scope("rt"))
      val routerImpls = routers.map { router =>
        val interpreter = router.interpreter.interpreter(routerParams)
        router.router(routerParams + DstBindingFactory.Namer(interpreter))
      }

      // Server sockets must not conflict
      for (srvs <- routerImpls.flatMap(_.servers).groupBy(_.addr).values)
        srvs match {
          case Seq(srv0, srv1, _*) => throw ConflictingPorts(srv0.addr, srv1.addr)
          case _ =>
        }

      Impl(
        routerImpls,
        namersByPrefix,
        tracer,
        telemeters,
        admin.getOrElse(AdminConfig())
      )
    }
  }

  /**
   * Private concrete implementation, to help protect compatibility if
   * the Linker api is extended.
   */
  private case class Impl(
    routers: Seq[Router],
    namers: Seq[(Path, Namer)],
    tracer: Tracer,
    telemeters: Seq[Telemeter],
    admin: AdminConfig
  ) extends Linker {
    override def configured[T: Stack.Param](t: T) =
      copy(routers = routers.map(_.configured(t)))
  }
}
