package io.buoyant.linkerd.admin

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http._
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.Buf
import io.buoyant.linkerd.Server
import com.twitter.util._
import io.buoyant.linkerd.{Router, Linker}

private[admin] object RouterHandler {
  object Codec {

    private[this] def mkModule() = {
      val module = new SimpleModule

      /**
       * Serializes a Router in the following form
       *
       * {
       *   label: "routerLabel",
       *   protocol: "http",
       *   servers: [...]
       * }
       */
      module.addSerializer(classOf[Router], new JsonSerializer[Router] {
        override def serialize(router: Router, json: JsonGenerator, p: SerializerProvider) {
          json.writeStartObject()
          json.writeStringField("label", router.label)
          json.writeStringField("protocol", router.protocol.name)
          json.writeFieldName("servers")
          json.writeRawValue(writeStr(router.servers))
          json.writeEndObject()
        }
      })

      /**
       * Serializes a Server in the following form
       *
       * {
       *   ip: "127.0.0.1",
       *   port: 4140,
       * }
       */
      module.addSerializer(classOf[Server], new JsonSerializer[Server] {
        override def serialize(server: Server, json: JsonGenerator, p: SerializerProvider): Unit = {
          json.writeStartObject()
          json.writeStringField("ip", server.ip.getHostAddress)
          json.writeNumberField("port", server.port)
          json.writeEndObject()
        }
      })

      module
    }

    private[this] val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(mkModule())

    def writeStr[T](t: T): String = mapper.writeValueAsString(t)
    def writeBuf[T](t: T): Buf = Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
  }
}

private[admin] class RouterHandler(
  linker: Linker
) extends Service[Request, Response] {

  import RouterHandler._

  private def err(status: Status) = Future.value(Response(status))

  def apply(req: Request): Future[Response] = req.method match {
    case Method.Get =>
      val rsp = req.response
      rsp.content = Codec.writeBuf(linker.routers)
      rsp.contentType = MediaType.Json
      Future.value(rsp)

    //TODO: include an Allow header for RFC compliance
    case _ => err(Status.MethodNotAllowed)
  }
}
