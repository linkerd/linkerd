package io.buoyant.linkerd.config

import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "protocol")
trait RouterConfig {
  def protocol: String

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

  def withDefaults(linker: LinkerConfig): RouterConfig

  /*
   * A router can only be validated in the context of other routers, as we need to check things
   * like port conflicts.
   */
  def validate(others: Seq[RouterConfig]): Seq[ConfigError] = {
    val routerErrors = {
      label match {
        case Some(labelStr) => (if (others exists { _.label == Some(labelStr) }) Seq(ConflictingLabels(labelStr)) else Nil)
        case None => Seq(MissingLabel)
      }
    }

    val serverErrors = {
      val prevServers = others flatMap {
        _.servers getOrElse Nil
      }
      servers match {
        case Some(seq) => seq flatMap { srv => srv.validate(prevServers) }
        case None => Nil
      }
    }
    routerErrors ++ serverErrors
  }


}

object RouterConfig {
  abstract class Defaults(base: RouterConfig, val linker: LinkerConfig) extends RouterConfig {
    override def label = base.label orElse Some(protocol)
    override def failFast = base.failFast orElse Some(false)
    override def baseDtab = base.baseDtab orElse linker.baseDtab
    override def servers =
      if (base.servers forall { _.isEmpty })
        Some(Seq(base.defaultServer))
      else
        base.servers
    override def defaultServer = base.defaultServer
    def protocol = base.protocol
  }
}

