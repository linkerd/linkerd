package io.buoyant.linkerd.admin

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util.{Future, Return, Throw, Try}
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, UnidentifiedRequest}

case class IdResult(path: Option[String] = None, dtab: Option[String] = None, error: Option[String] = None)

object Json {
  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.setSerializationInclusion(Include.NON_EMPTY)

  def write[T](t: T): Buf =
    Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
}

class HttpIdentifierHandler(
  identifiersByLabel: Map[String, RoutingFactory.Identifier[Request]]
) extends Service[Request, Response] {

  def apply(req: Request): Future[Response] = {
    val results = identifiersByLabel.mapValues { id =>
      id(req).transform {
        case Return(IdentifiedRequest(dst, req1)) =>
          Future.value(IdResult(Some(dst.path.show), Some(dst.dtab.show)))
        case Return(unidentified: UnidentifiedRequest[Request]) =>
          Future.value(IdResult(error = Some(unidentified.reason)))
        case Throw(e) =>
          Future.value(IdResult(error = Some(e.getMessage)))
      }
    }

    Future.collect(results).map { r =>
      val rsp = Response()
      rsp.content = Json.write(r)
      rsp.contentType = MediaType.Json
      rsp
    }
  }
}
