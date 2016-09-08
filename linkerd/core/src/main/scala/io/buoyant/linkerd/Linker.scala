package io.buoyant.linkerd

import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{param, Path, Namer, Stack}
import com.twitter.finagle.stats.{BroadcastStatsReceiver, DefaultStatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing.{debugTrace => fDebugTrace, NullTracer, DefaultTracer, BroadcastTracer, Tracer}
import com.twitter.finagle.util.LoadService
import com.twitter.logging.Logger
import io.buoyant.admin.AdminConfig
import io.buoyant.config._
import io.buoyant.namer.Param.Namers
import io.buoyant.namer._
import io.buoyant.telemetry.{TelemeterInitializer, TelemeterConfig, Telemeter}

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
    tlsClient: Seq[TlsClientInitializer] = Nil,
    tracer: Seq[TracerInitializer] = Nil,
    identifier: Seq[IdentifierInitializer] = Nil,
    classifier: Seq[ResponseClassifierInitializer] = Nil,
    telemetry: Seq[TelemeterInitializer] = Nil,
    announcer: Seq[AnnouncerInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(protocol, namer, interpreter, tlsClient, tracer, identifier, classifier, telemetry, announcer)

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

      val telemeters = telemetry.map(_.map(_.mk(Stack.Params.empty)))

      // Telemeters may provide StatsReceivers.  Note that if all
      // telemeters provide implementations that do not use the
      // default Metrics registry, linker stats may be missing from
      // /admin/metrics.json
      val stats =
        telemeters.getOrElse(Nil).collect { case t if !t.stats.isNull => t.stats } match {
          case Nil =>
            log.info(s"Using default stats receiver")
            DefaultStatsReceiver
          case receivers =>
            for (r <- receivers) log.info(s"Using stats receiver: $r")
            BroadcastStatsReceiver(receivers)
        }

      // Similarly, tracers may be provided by telemeters OR by
      // 'tracers' configuration.
      //
      // TODO the TracerInitializer API should be killed and these
      // modules should be converted to Telemeters.
      val configuredTracers = tracers.map { tracers =>
        tracers.map { t =>
          // override the global {com.twitter.finagle.tracing.debugTrace} flag
          fDebugTrace.parse(t.debugTrace.toString)
          t.newTracer()
        }
      }
      val telemeterTracers = telemeters.map { ts =>
        ts.collect { case t if !t.tracer.isNull => t.tracer }
      }
      val tracer: Tracer = (configuredTracers, telemeterTracers) match {
        case (None, None) =>
          log.info(s"Using default tracer")
          DefaultTracer
        case (tracers0, tracers1) =>
          val tracers = (tracers0 ++ tracers1).flatten.toSeq
          for (t <- tracers) log.info(s"Using tracer: $t")
          BroadcastTracer(tracers)
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
        val interpreter = router.interpreter.newInterpreter(routerParams)
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
        telemeters.getOrElse(Nil),
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
