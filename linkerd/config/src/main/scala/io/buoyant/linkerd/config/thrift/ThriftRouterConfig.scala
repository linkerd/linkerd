package io.buoyant.linkerd.config.thrift

import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.ObjectMapper
import io.buoyant.linkerd.config.{ConfigRegistrar, RouterConfig}

@JsonTypeName("thrift")
case class ThriftRouterConfig(
  thriftFramed: Option[Boolean],
  thriftMethodInDst: Option[Boolean]
) extends RouterConfig

class ThriftRouterConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(classOf[ThriftRouterConfig])
  }
}
