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

  def parse(config: String, configInitializers: Seq[ConfigInitializer]): LinkerConfig = {
    val mapper = Parser.objectMapper(config)
    // Subtypes must not conflict
    configInitializers.groupBy(_.configId).collect {
      case (id, cis) if cis.size > 1 =>
        throw ConflictingSubtypes(cis(0).namedType, cis(1).namedType)
    }
    for (ci <- configInitializers) ci.registerSubtypes(mapper)
    // TODO: Store the LinkerConfig so that it can be serialized out later
    mapper.readValue[LinkerConfig](config)
  }

  def load(config: String, configInitializers: Seq[ConfigInitializer]): Linker = {
    parse(config, configInitializers).mk
  }

  def parse(config: String): LinkerConfig = {
    val protocols = LoadService[ProtocolInitializer]
    val namers = LoadService[NamerInitializer]
    val clientTls = LoadService[TlsClientInitializer]
    val tracers = LoadService[TracerInitializer]
    parse(config, protocols ++ namers ++ clientTls ++ tracers)
  }

  def load(config: String): Linker = {
    parse(config).mk
  }

  case class LinkerConfig(
    namers: Option[Seq[NamerConfig]],
    routers: Seq[RouterConfig],
    tracers: Option[Seq[TracerConfig]],
    admin: Option[Admin]
  ) {
    def mk: Linker = {

      val tracerImpls = tracers.map(_.map(_.newTracer()))
      val tracer: Tracer = tracerImpls match {
        case Some(Nil) => NullTracer
        case Some(Seq(tracer)) => tracer
        case Some(tracers) => BroadcastTracer(tracers)
        case None => DefaultTracer
      }

      val namerParams = Stack.Params.empty + param.Tracer(tracer)

      val interpreter = namers.map(nameInterpreter(namerParams))

      val params = namerParams
        .maybeWith(interpreter.map(DstBindingFactory.Namer(_)))

      // At least one router must be specified
      if (routers.isEmpty) {
        throw NoRoutersSpecified
      }

      // Router labels must not conflict
      routers.groupBy(_.label).foreach {
        case (label, rts) =>
          if (rts.size > 1) throw ConflictingLabels(label)
      }

      val routerImpls = routers.map(_.router(params))

      // Server sockets must not conflict
      routerImpls.flatMap(_.servers).groupBy(_.addr).collect {
        case (port, svrs) =>
          if (svrs.size > 1) throw ConflictingPorts(svrs(0).addr, svrs(1).addr)
      }

      new Impl(routerImpls, interpreter.getOrElse(DefaultInterpreter), tracer, admin.getOrElse(Admin()))
    }
  }

  def nameInterpreter(params: Stack.Params)(namers: Seq[NamerConfig]): NameInterpreter =
    Interpreter(namers.map { cfg =>
      cfg.prefix -> cfg.newNamer(params)
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
