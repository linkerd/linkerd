package io.buoyant.linkerd.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.twitter.finagle.{Dtab, Stack}

/**
  * LinkerConfig can configure some "default" settings for its routers; this trait
  * allows those parameters to be shared.
  */
trait CommonRouterConfig {
  var baseDtab: Option[String] = None
  var failFast: Option[Boolean] = None
  var timeoutMs: Option[Int] = None
}

/*
 * RouterConfig implements the generic configuration for a [[Router]]. All
 * Routers must have a protocol (i.e. Thrift, HTTP); those are represented
 * as subclasses of RouterConfig.
 *
 * To implement a protocol:
 * * Create a case class subclassing the RouterConfig trait that adds any
 *   extra properties which should be serialized/deserialized to configuration.
 *   This must implement a `protocol` method, which is described below.
 * * Implement a ConfigRegistrar class, with a Register method to add
 *   the case class above to Jackson's ObjectMapper.
 * * Create a case class (which can be the same as above
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "protocol")
trait RouterConfig extends CommonRouterConfig {
  // These are vars to allow Jackson to deserialize instances without subclasses needing to know about
  // the base trait's properties.
  var label: Option[String] = None
  var dstPrefix: Option[String] = None

  def servers: Option[Seq[ServerConfig]]

  def defaultServer: ServerConfig = BaseServerConfig(None, None)

  def withDefaults(linker: LinkerConfig): RouterConfig.Defaults =
    new RouterConfig.Defaults(this, protocol, linker)

  def protocol: RouterProtocol
}

trait RouterProtocol {
  def name: String
  def validated: ValidatedConfig[RouterProtocol]
}

object RouterConfig {
  import cats.Apply
  import cats.std.list._

  class Defaults(base: RouterConfig, protocol: RouterProtocol, linker: LinkerConfig) {
    def label: String = base.label getOrElse protocol.name
    def failFast: Boolean = base.failFast orElse linker.failFast getOrElse false
    def baseDtab: String = base.baseDtab orElse linker.baseDtab getOrElse ""
    def servers: Seq[ServerConfig] = base.servers getOrElse Seq(base.defaultServer)

    def validated(others: Seq[RouterConfig.Defaults]): ValidatedConfig[RouterConfig.Validated] = {


      def validatedBaseDtab: ValidatedConfig[Dtab] = {
        try {
          valid(Dtab.read(baseDtab))
        } catch {
          case ex: IllegalArgumentException => invalid(InvalidDtab(baseDtab, ex))
        }
      }

      def validatedLabel: ValidatedConfig[String] =
        if (others.exists(_.label == label))
          invalid(ConflictingLabels(label))
        else
          valid(label)

      // TODO: determine if we need to optimize this by passing it in the foldLeft
      val prevServers = for {
        router <- others
        server <- router.servers
      } yield server.withDefaults(this)

      val validatedServers = ServerConfig.validateServers(servers, this, prevServers)

      Apply[ValidatedConfig].map4(validatedLabel, validatedBaseDtab, protocol.validated, validatedServers) { case (label, dtab, protocol, servers) =>
        new Validated(label, failFast, dtab, protocol, servers)
      }
    }
  }

  class Validated(
    val label: String,
    val failFast: Boolean,
    val baseDtab: Dtab,
    val protocol: RouterProtocol,
    val servers: Seq[ServerConfig.Validated]
  )

  def validateRouters(linker: LinkerConfig)(routers: Seq[RouterConfig]): ValidatedConfig[Seq[RouterConfig.Validated]] = {
    val (validatedRouters, _) = routers.foldLeft((valid(Seq.empty[Validated]), Seq.empty[RouterConfig.Defaults])) {
      case ((accum, prev), r) =>
        val defaulted = r.withDefaults(linker)
        (Apply[ValidatedConfig].map2(accum, defaulted.validated(prev))(_ :+ _), prev :+ defaulted)
    }
    validatedRouters
  }
}

trait RouterParams {

  def apply(): Stack.Params
}
