package io.buoyant.linkerd.config.thrift

import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import io.buoyant.linkerd.config._



trait ThriftRouterConfig extends RouterConfig {
  override def protocol = ThriftRouterConfig.Protocol
  def thriftFramed: Option[Boolean]
  def thriftMethodInDst: Option[Boolean]
  def servers: Option[Seq[ServerConfig]]
  def withDefaults(linker: LinkerConfig): ThriftRouterConfig = new ThriftRouterConfig.Defaults(this, linker)
}

object ThriftRouterConfig {
  val Protocol = "thrift"

  case class Impl(
    thriftFramed: Option[Boolean],
    thriftMethodInDst: Option[Boolean],
    servers: Option[Seq[BaseServerConfig]]
  ) extends ThriftRouterConfig

  class Defaults(base: ThriftRouterConfig, linker: LinkerConfig) extends RouterConfig.Defaults(base, linker) with ThriftRouterConfig {
    override def thriftFramed = base.thriftFramed orElse Some(false)
    override def thriftMethodInDst = base.thriftMethodInDst orElse Some(false)
  }
}

class ThriftRouterConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(new NamedType(classOf[ThriftRouterConfig.Impl], ThriftRouterConfig.Protocol))
  }
}
