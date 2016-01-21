package io.buoyant.linkerd.config.http

import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.ObjectMapper
import io.buoyant.linkerd.config.{ConfigRegistrar, ServerConfig, RouterConfig}

@JsonTypeName("http")
case class HttpRouterConfig(
  httpUriInDst: Boolean,
  servers: Seq[ServerConfig]
) extends RouterConfig

class HttpRouterConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(classOf[HttpRouterConfig])
  }
}

