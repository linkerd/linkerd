package io.buoyant.linkerd.config

import cats.data.{NonEmptyList, ValidatedNel}
import cats.data.Validated._
import cats.implicits._

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.twitter.finagle.{Dtab, Stack}

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
trait RouterConfig {
  // We do this to allow case classes to extend the trait without needing to know its properties,
  // while still allowing Jackson to correctly serialize/deserialize instances.
  private var _label: Option[String] = None
  def label: Option[String] = _label
  def label_=(l: Option[String]): Unit = _label = l

  private var _baseDtab: Option[String] = None
  def baseDtab: Option[String] = _baseDtab
  def baseDtab_=(dtab: Option[String]): Unit = { _baseDtab = dtab }

  private var _dstPrefix: Option[String] = None
  def dstPrefix: Option[String] = _dstPrefix
  def dstPrefix_=(prefix: Option[String]): Unit = { _dstPrefix = prefix }

  private var _failFast: Option[Boolean] = None
  def failFast: Option[Boolean] = _failFast
  def failFast_=(ff: Option[Boolean]): Unit = { _failFast = ff }

  private var _timeoutMs: Option[Int] = None
  def timeoutMs: Option[Int] = _timeoutMs
  def timeoutMs_=(timeout: Option[Int]): Unit = { _timeoutMs = timeout }

  def servers: Option[Seq[ServerConfig]]

  def defaultServer: ServerConfig = BaseServerConfig(None, None)

  def withDefaults(linker: LinkerConfig): RouterConfig.Defaults =
    new RouterConfig.Defaults(this, protocol, linker)

  def protocol: RouterProtocol
}

trait RouterProtocol {
  def name: String
  def validated: ValidatedNel[ConfigError, RouterProtocol]
}

object RouterConfig {
  class Defaults(base: RouterConfig, protocol: RouterProtocol, linker: LinkerConfig) {
    def label: String = base.label getOrElse protocol.name
    def failFast: Boolean = base.failFast orElse linker.failFast getOrElse false
    def baseDtab: String = base.baseDtab orElse linker.baseDtab getOrElse ""
    def servers: Seq[ServerConfig] = base.servers getOrElse Seq(base.defaultServer)

    def validated(others: Seq[RouterConfig.Defaults]): ValidatedNel[ConfigError, RouterConfig.Validated] = {
      def validatedBaseDtab: ValidatedNel[ConfigError, Dtab] = {
        catchOnly[IllegalArgumentException] {
          Dtab.read(baseDtab)
        }.leftMap { ex =>
          NonEmptyList(InvalidDtab(baseDtab, ex))
        }
      }

      def validatedLabel: ValidatedNel[ConfigError, String] =
        if (others.exists(_.label == label))
          invalidNel(ConflictingLabels(label))
        else
          valid(label)

      // TODO: determine if we need to optimize this by passing it in the
      // foldLeft
      val prevServers = for {
        router <- others
        server <- router.servers
      } yield server.withDefaults(this)

      (validatedLabel |@|
        validatedBaseDtab |@|
        protocol.validated |@|
        ServerConfig.validateServers(servers, this, prevServers)).map {
          case (l, dt, pr, srv) => new Validated(l, failFast, dt, pr, srv)
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

  def validateRouters(linker: LinkerConfig)(routers: Seq[RouterConfig]): ValidatedNel[ConfigError, Seq[RouterConfig.Validated]] = {
    // TODO implement, this just gets it compiling
    val (validatedRouters, _) = routers.foldLeft((valid[ConfigError, Seq[RouterConfig.Validated]](Nil).toValidatedNel, Seq.empty[RouterConfig.Defaults])) {
      case ((accum, prev), r) =>
        val defaulted = r.withDefaults(linker)
        ((accum |@| defaulted.validated(prev)).map(_ :+ _), prev :+ defaulted)
    }
    validatedRouters
  }
}

trait RouterParams {

  def apply(): Stack.Params
}
