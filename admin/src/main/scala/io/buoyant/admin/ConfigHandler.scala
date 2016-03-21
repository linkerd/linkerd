package io.buoyant.admin

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.util.Future
import io.buoyant.config.{ConfigInitializer, Parser}

class ConfigHandler(config: Any, configInitializers: Iterable[Seq[ConfigInitializer]])
  extends Service[Request, Response] {

  val mapper = Parser.jsonObjectMapper(configInitializers)

  override def apply(request: Request): Future[Response] = {
    val response = Response()
    response.contentType = MediaType.Json + ";charset=UTF-8"
    response.contentString = mapper.writeValueAsString(config)
    Future.value(response)
  }
}
