package io.buoyant.linkerd

import com.twitter.finagle.{param, Path, Namer, Stack}
import com.twitter.finagle.buoyant.DstBindingFactory
import com.twitter.finagle.naming.{DefaultInterpreter, NameInterpreter}
import com.twitter.finagle.tracing.{NullTracer, DefaultTracer, BroadcastTracer, Tracer}
import com.twitter.finagle.util.LoadService
import io.buoyant.linkerd.config._

/**
 * Represents the total configuration of a Linkerd process.
 */
trait Linker {
  def routers: Seq[Router]
  def namers: Seq[(Path, Namer)]
  def admin: Admin
  def tracer: Tracer
  def configured[T: Stack.Param](t: T): Linker
}

object Linker {

  lazy val configInitializers: Seq[ConfigInitializer] = {
    val protocols = LoadService[ProtocolInitializer]
    val namers = LoadService[NamerInitializer]
    val interpreters = LoadService[InterpreterInitializer] :+ new DefaultInterpreterInitializer
    val clientTls = LoadService[TlsClientInitializer]
    val tracers = LoadService[TracerInitializer]
    protocols ++ namers ++ interpreters ++ clientTls ++ tracers
  }

  def parse(config: String, configInitializers: Seq[ConfigInitializer]): LinkerConfig = {
    val mapper = Parser.objectMapper(config, configInitializers)

    mapper.readValue[LinkerConfig](config)
  }

  def load(config: String, configInitializers: Seq[ConfigInitializer]): Linker =
    parse(config, configInitializers).mk()

  def parse(config: String): LinkerConfig = {
    parse(config, configInitializers)
  }

  def load(config: String): Linker = parse(config).mk()

  case class LinkerConfig(
    namers: Option[Seq[NamerConfig]],
    routers: Seq[RouterConfig],
    tracers: Option[Seq[TracerConfig]],
    admin: Option[Admin]
  ) {
    def mk(): Linker = {
      // At least one router must be specified
      if (routers.isEmpty) throw NoRoutersSpecified

      val tracerImpls = tracers.map(_.map(_.newTracer()))
      val tracer: Tracer = tracerImpls match {
        case Some(Nil) => NullTracer
        case Some(Seq(tracer)) => tracer
        case Some(tracers) => BroadcastTracer(tracers)
        case None => DefaultTracer
      }

      val namerParams = Stack.Params.empty + param.Tracer(tracer)
      val namersByPrefix = namers.getOrElse(Nil).reverse.map { namer =>
        namer.prefix -> namer.newNamer(namerParams)
      }

      // Router labels must not conflict
      routers.groupBy(_.label).foreach {
        case (label, rts) =>
          if (rts.size > 1) throw ConflictingLabels(label)
      }

      val routerParams = namerParams + Router.Namers(namersByPrefix)
      val routerImpls = routers.map { router =>
        val interpreter = router.interpreter.newInterpreter(routerParams)
        router.router(routerParams + DstBindingFactory.Namer(interpreter))
      }

      // Server sockets must not conflict
      routerImpls.flatMap(_.servers).groupBy(_.addr).foreach {
        case (port, svrs) =>
          if (svrs.size > 1) throw ConflictingPorts(svrs(0).addr, svrs(1).addr)
      }

      new Impl(routerImpls, namersByPrefix, tracer, admin.getOrElse(Admin()))
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
    admin: Admin
  ) extends Linker {
    override def configured[T: Stack.Param](t: T) =
      copy(routers = routers.map(_.configured(t)))
  }
}
