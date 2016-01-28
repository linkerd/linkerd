package io.buoyant.linkerd.config.http

import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import io.buoyant.linkerd.config._

trait HttpRouterConfig extends RouterConfig {
  override def protocol = HttpRouterConfig.Protocol
  def httpUriInDst: Option[Boolean]
  def servers: Option[Seq[ServerConfig]]
  def withDefaults(linker: LinkerConfig) = new HttpRouterConfig.Defaults(this, linker)
}

object HttpRouterConfig {
  val Protocol = "http"
  case class Impl(httpUriInDst: Option[Boolean], servers: Option[Seq[BaseServerConfig]]) extends HttpRouterConfig
  class Defaults(base: HttpRouterConfig, linker: LinkerConfig) extends RouterConfig.Defaults(base, linker) with HttpRouterConfig {
    override def httpUriInDst = base.httpUriInDst orElse Some(false)
  }
}


class HttpRouterConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(new NamedType(classOf[HttpRouterConfig.Impl], HttpRouterConfig.Protocol))
  }
}

