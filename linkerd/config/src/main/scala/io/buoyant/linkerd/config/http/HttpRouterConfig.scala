package io.buoyant.linkerd.config.http

import cats.data.{ValidatedNel, Validated}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import io.buoyant.linkerd.config._
import cats.data.Validated._

case class HttpRouterConfig(
  httpUriInDst: Option[Boolean],
  servers: Option[Seq[BaseServerConfig]]
) extends RouterConfig {
  val protocol = HttpRouterConfig.Protocol(httpUriInDst getOrElse false)
}

object HttpRouterConfig {
  object Protocol {
    val name = "http"
  }

  case class Protocol(httpUriInDst: Boolean) extends RouterProtocol {
    def name: String = Protocol.name
    // There are no invalid HTTP protocol configurations.
    def validated: ValidatedNel[ConfigError, Protocol] = valid(this)
  }
}

class HttpRouterConfigRegistrar extends ConfigRegistrar {
  def register(mapper: ObjectMapper): Unit = {
    mapper.registerSubtypes(new NamedType(classOf[HttpRouterConfig], HttpRouterConfig.Protocol.name))
  }
}

