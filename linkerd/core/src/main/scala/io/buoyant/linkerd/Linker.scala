package io.buoyant.linkerd

import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.{BroadcastStatsReceiver, LoadedStatsReceiver}
import com.twitter.finagle.tracing.{BroadcastTracer, DefaultTracer, Tracer}
import com.twitter.finagle.util.LoadService
import com.twitter.finagle.{Namer, Path, Stack, param}
import com.twitter.logging.Logger
import io.buoyant.admin.{Admin, AdminConfig}
import io.buoyant.config._
import io.buoyant.namer.Param.Namers
import io.buoyant.namer._
import io.buoyant.telemetry._
import java.net.InetSocketAddress
import scala.util.control.NoStackTrace

/**
 * Represents the total configuration of a Linkerd process.
 */
trait Linker {
  def routers: Seq[Router]
  def namers: Seq[(Path, Namer)]
  def admin: Admin
  def tracer: Tracer
  def telemeters: Seq[Telemeter]
  def configured[T: Stack.Param](t: T): Linker
}

object Linker {
  private[this] val log = Logger()

  private[this] val DefaultAdminAddress = new InetSocketAddress(9990)
  private[this] val DefaultAdminConfig = AdminConfig()

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
    announcer: Seq[AnnouncerInitializer] = Nil,
    failureAccrual: Seq[FailureAccrualInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(protocol, namer, interpreter, tlsClient, tracer, identifier, transformer, classifier, telemetry, announcer, failureAccrual)

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
    LoadService[AnnouncerInitializer],
    LoadService[FailureAccrualInitializer]
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

    def mk(defaultTelemeter: Telemeter = NullTelemeter): Linker = {
      // At least one router must be specified
      if (routers.isEmpty) throw NoRoutersSpecified

      val telemeters = telemetry match {
        case None => Seq(defaultTelemeter)
        case Some(telemeters) => telemeters.map {
          case t if t.disabled =>
            val msg = s"The ${t.getClass.getCanonicalName} telemeter is experimental and must be " +
              "explicitly enabled by setting the `experimental' parameter to `true'."
            throw new IllegalArgumentException(msg) with NoStackTrace
          case t => t.mk(Stack.Params.empty)
        }
      }

      // Telemeters may provide StatsReceivers.
      val stats = mkStats(telemeters)
      LoadedStatsReceiver.self = stats

      // Tracers may be provided by telemeters OR by 'tracers'
      // configuration.
      //
      // TODO the TracerInitializer API should be killed and these
      // modules should be converted to Telemeters.
      val tracer = mkTracer(telemeters)
      DefaultTracer.self = tracer

      val params = Stack.Params.empty + param.Tracer(tracer) + param.Stats(stats)

      val namersByPrefix = mkNamers(params + param.Stats(stats.scope("namer")))
      NameInterpreter.setGlobal(ConfiguredNamersInterpreter(namersByPrefix))

      val routerImpls = mkRouters(params + Namers(namersByPrefix) + param.Stats(stats.scope("rt")))

      val adminImpl = admin.getOrElse(DefaultAdminConfig).mk(DefaultAdminAddress)

      Impl(routerImpls, namersByPrefix, tracer, telemeters, adminImpl)
    }

    private[this] def mkStats(telemeters: Seq[Telemeter]) = {
      val receivers = telemeters.collect { case t if !t.stats.isNull => t.stats }
      for (r <- receivers) log.debug("stats: %s", r)
      BroadcastStatsReceiver(receivers)
    }

    private[this] def mkTracer(telemeters: Seq[Telemeter]) = {
      val all = tracers.getOrElse(Nil).map(_.newTracer()) ++
        telemeters.collect { case t if !t.tracer.isNull => t.tracer }
      for (t <- all) log.info("tracer: %s", t)
      BroadcastTracer(all)
    }

    private[this] def mkNamers(params: Stack.Params) = {
      namers.getOrElse(Nil).reverse.map {
        case n if n.disabled =>
          val msg = s"The ${n.prefix.show} namer is experimental and must be " +
            "explicitly enabled by setting the `experimental' parameter to `true'."
          throw new IllegalArgumentException(msg) with NoStackTrace

        case n => n.prefix -> n.mk(params)
      }
    }

    private[this] def mkRouters(params: Stack.Params) = {
      // Router labels must not conflict
      for ((label, rts) <- routers.groupBy(_.label))
        if (rts.size > 1) throw ConflictingLabels(label)

      for (r <- routers) {
        if (r.disabled) {
          val msg = s"The ${r.protocol.name} protocol is experimental and must be " +
            "explicitly enabled by setting the `experimental' parameter to `true' on each router."
          throw new IllegalArgumentException(msg) with NoStackTrace
        }
      }

      val impls = routers.map { router =>
        val interpreter = router.interpreter.interpreter(params)
        router.router(params + DstBindingFactory.Namer(interpreter))
      }

      // Server sockets must not conflict
      impls.flatMap(_.servers).groupBy(_.addr).values.foreach {
        case Seq(srv0, srv1, _*) => throw ConflictingPorts(srv0.addr, srv1.addr)
        case _ =>
      }

      impls
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
    admin: Admin
  ) extends Linker {
    override def configured[T: Stack.Param](t: T) =
      copy(routers = routers.map(_.configured(t)))
  }
}
