package io.buoyant.linkerd.config.thrift

import cats.data.Validated._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import io.buoyant.linkerd.config._

case class ThriftRouterConfig(
  thriftFramed: Option[Boolean],
  thriftMethodInDst: Option[Boolean],
  // TODO: implement ThriftServerConfig
  servers: Option[Seq[BaseServerConfig]]
) extends RouterConfig {
  val protocol = ThriftRouterConfig.Protocol(
    thriftFramed getOrElse false,
    thriftMethodInDst getOrElse false
  )
}

object ThriftRouterConfig {
  object Protocol {
    val name = "thrift"
  }

  case class Protocol(thriftFramed: Boolean, thriftMethodInDst: Boolean) extends RouterProtocol {
    def name = Protocol.name
    // None of the Thrift protocol configuration requires validation.
    def validated = valid(this)
  }
}

class ThriftRouterConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(new NamedType(classOf[ThriftRouterConfig], ThriftRouterConfig.Protocol.name))
  }
}
