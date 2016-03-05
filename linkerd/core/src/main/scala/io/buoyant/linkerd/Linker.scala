package io.buoyant.linkerd

import com.twitter.finagle.{param, Stack}
import com.twitter.finagle.Stack.Param
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
  def interpreter: NameInterpreter
  def admin: Admin
  def tracer: Tracer
  def configured[T: Stack.Param](t: T): Linker
}

object Linker {

  lazy val configInitializers: Seq[ConfigInitializer] = {
    val protocols = LoadService[ProtocolInitializer]
    val namers = LoadService[NamerInitializer]
    val clientTls = LoadService[TlsClientInitializer]
    val tracers = LoadService[TracerInitializer]
    protocols ++ namers ++ clientTls ++ tracers
  }

  def parse(config: String, configInitializers: Seq[ConfigInitializer]): LinkerConfig = {
    val mapper = Parser.objectMapper(config, configInitializers)

    mapper.readValue[LinkerConfig](config)
  }

  def load(config: String, configInitializers: Seq[ConfigInitializer]): Linker = {
    parse(config, configInitializers).mk
  }

  def parse(config: String): LinkerConfig = {
    parse(config, configInitializers)
  }

  def load(config: String): Linker = {
    parse(config).mk
  }

  case class LinkerConfig(
    namers: Option[Seq[NamingFactoryConfig]],
    routers: Seq[RouterConfig],
    tracers: Option[Seq[TracerConfig]],
    admin: Option[Admin]
  ) {
    def mk: Linker = {
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
      val interpreter = mkNameInterpreter(namers.getOrElse(Nil), namerParams)

      val params = namerParams + DstBindingFactory.Namer(interpreter)

      // Router labels must not conflict
      routers.groupBy(_.label).foreach {
        case (label, rts) =>
          if (rts.size > 1) throw ConflictingLabels(label)
      }

      val routerImpls = routers.map(_.router(params))

      // Server sockets must not conflict
      routerImpls.flatMap(_.servers).groupBy(_.addr).foreach {
        case (port, svrs) =>
          if (svrs.size > 1) throw ConflictingPorts(svrs(0).addr, svrs(1).addr)
      }

      new Impl(routerImpls, interpreter, tracer, admin.getOrElse(Admin()))
    }
  }

  // namers may contain a single NameInterpreter or a list of Namers
  private[this] case class NamingConfig(
    interpreters: Seq[NamingFactory.Interpreter] = Nil,
    namers: Seq[NamingFactory.Namer] = Nil
  ) {
    def +(nf: NamingFactory): NamingConfig = nf match {
      case i: NamingFactory.Interpreter => copy(interpreters = interpreters :+ i)
      case n: NamingFactory.Namer => copy(namers = namers :+ n)
    }
  }

  private[linkerd] def mkNameInterpreter(
    configs: Seq[NamingFactoryConfig],
    params: Stack.Params
  ): NameInterpreter =
    configs.foldLeft(NamingConfig())(_ + _.newFactory(params)) match {
      case NamingConfig(Nil, Nil) =>
        DefaultInterpreter

      case NamingConfig(Seq(interpreter), Nil) =>
        interpreter.mk()

      case NamingConfig(Nil, namers) if namers.nonEmpty =>
        val namersByPfx = namers.map { case NamingFactory.Namer(_, pfx, mk) => pfx -> mk() }
        // Namers are reversed so that last-defined-namer wins
        ConfiguredNamersInterpreter(namersByPfx.reverse)

      case NamingConfig(interpreters, _) if interpreters.size > 1 =>
        throw MultipleInterpreters(interpreters)

      case NamingConfig(interpreters, namers) =>
        throw InterpretersWithNamers(interpreters, namers)
    }

  case class MultipleInterpreters(
    interpeters: Seq[NamingFactory.Interpreter]
  ) extends Exception({
    val kinds = interpeters.map(_.kind).mkString(", ")
    s"at most one of the following namers may be configured: $kinds"
  })

  case class InterpretersWithNamers(
    interpeters: Seq[NamingFactory.Interpreter],
    namers: Seq[NamingFactory.Namer]
  ) extends Exception({
    val ikinds = interpeters.map(_.kind).mkString(", ")
    val nkinds = namers.map(_.kind).mkString(", ")
    s"interpreters ($ikinds) may not be specified with namers ($nkinds)"
  })

  /**
   * Private concrete implementation, to help protect compatibility if
   * the Linker api is extended.
   */
  private case class Impl(
    routers: Seq[Router],
    interpreter: NameInterpreter,
    tracer: Tracer,
    admin: Admin
  ) extends Linker {
    override def configured[T: Param](t: T) =
      copy(routers = routers.map(_.configured(t)))
  }
}
