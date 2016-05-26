package io.buoyant.linkerd

import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.{param, Path, Namer, Stack}
import com.twitter.finagle.tracing.{debugTrace => fDebugTrace, NullTracer, DefaultTracer, BroadcastTracer, Tracer}
import com.twitter.finagle.util.LoadService
import com.twitter.logging.Logger
import io.buoyant.admin.AdminConfig
import io.buoyant.config._
import io.buoyant.namer.Param.Namers
import io.buoyant.namer._

/**
 * Represents the total configuration of a Linkerd process.
 */
trait Linker {
  def routers: Seq[Router]
  def namers: Seq[(Path, Namer)]
  def admin: AdminConfig
  def tracer: Tracer
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
    classifier: Seq[ResponseClassifierInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(protocol, namer, interpreter, tlsClient, tracer, identifier, classifier)

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
    LoadService[ResponseClassifierInitializer]
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
    admin: Option[AdminConfig]
  ) {
    def mk(): Linker = {
      // At least one router must be specified
      if (routers.isEmpty) throw NoRoutersSpecified

      val tracer: Tracer = tracers.map(_.map { t =>
        // override the global {com.twitter.finagle.tracing.debugTrace} flag
        fDebugTrace.parse(t.debugTrace.toString)
        t.newTracer()
      }) match {
        case Some(Nil) => NullTracer
        case Some(Seq(tracer)) => tracer
        case Some(tracers) => BroadcastTracer(tracers)
        case None => DefaultTracer
      }

      log.info(s"Loading tracer: $tracer")

      val namerParams = Stack.Params.empty + param.Tracer(tracer)
      val namersByPrefix = namers.getOrElse(Nil).reverse.map { namer =>
        if (namer.disabled) throw new IllegalArgumentException(
          s"""The ${namer.prefix.show} namer is experimental and must be explicitly enabled by setting the "experimental" parameter to true."""
        )
        namer.prefix -> namer.newNamer(namerParams)
      }

      // Router labels must not conflict
      for ((label, rts) <- routers.groupBy(_.label))
        if (rts.size > 1) throw ConflictingLabels(label)

      val routerParams = namerParams + Namers(namersByPrefix)
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

      new Impl(routerImpls, namersByPrefix, tracer, admin.getOrElse(AdminConfig()))
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
    admin: AdminConfig
  ) extends Linker {
    override def configured[T: Stack.Param](t: T) =
      copy(routers = routers.map(_.configured(t)))
  }
}
