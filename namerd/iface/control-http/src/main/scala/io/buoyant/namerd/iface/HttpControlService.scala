package io.buoyant.namerd.iface

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http._
import com.twitter.finagle.{Dentry, Dtab, NameTree, Path, Service}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.DtabStore.{DtabVersionMismatchException, DtabNamespaceDoesNotExist}
import io.buoyant.namerd.{DtabStore, VersionedDtab}

object HttpControlService {

  object Json {
    private[this] def mkModule() = {
      val module = new SimpleModule

      module.addSerializer(classOf[Path], new JsonSerializer[Path] {
        override def serialize(path: Path, json: JsonGenerator, p: SerializerProvider): Unit =
          json.writeString(path.show)
      })
      module.addDeserializer(classOf[Path], new JsonDeserializer[Path] {
        override def deserialize(json: JsonParser, ctx: DeserializationContext) =
          Path.read(json.getValueAsString)
      })

      module.addSerializer(classOf[NameTree[Path]], new JsonSerializer[NameTree[Path]] {
        override def serialize(
          nameTree: NameTree[Path],
          json: JsonGenerator,
          p: SerializerProvider
        ): Unit = json.writeString(nameTree.show)
      })

      module.addDeserializer(classOf[NameTree[Path]], new JsonDeserializer[NameTree[Path]] {
        override def deserialize(json: JsonParser, ctx: DeserializationContext) =
          NameTree.read(json.getValueAsString)
      })

      module
    }

    private[this] val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(mkModule())

    def read[T: Manifest](buf: Buf): Try[T] = {
      val Buf.ByteBuffer.Owned(bb) = Buf.ByteBuffer.coerce(buf)
      Try { mapper.readValue[T](bb.array) }
    }

    def write[T](t: T): Buf =
      Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
  }

  sealed trait DtabCodec {
    def contentTypes: Set[String]
    def write(dtab: Dtab): Buf
    def read(buf: Buf): Try[Dtab]
  }
  object DtabCodec {

    object Json extends DtabCodec {
      val contentTypes = Set(MediaType.Json)
      def write(dtab: Dtab) = HttpControlService.Json.write(dtab)
      def read(buf: Buf) =
        HttpControlService.Json.read[IndexedSeq[Dentry]](buf).map(Dtab(_))
    }

    object Text extends DtabCodec {
      val contentTypes = Set("application/dtab", MediaType.Txt)
      def write(dtab: Dtab) = Buf.Utf8(dtab.show)
      def read(buf: Buf) = {
        val Buf.Utf8(d) = buf
        Try { Dtab.read(d) }
      }
    }

    def accept(types: Seq[String]): Option[(String, DtabCodec)] =
      types.map(_.toLowerCase).foldLeft[Option[(String, DtabCodec)]](None) {
        case (None, ct) => byContentType(ct).map(ct -> _)
        case (t, _) => t
      }

    def byContentType(ct: String): Option[DtabCodec] =
      if (Json.contentTypes(ct)) Some(DtabCodec.Json)
      else if (Text.contentTypes(ct)) Some(DtabCodec.Text)
      else None

    val default = (MediaType.Json, Json)
  }

  object DtabUri {
    val prefix = "/api/1/dtabs"
    val prefixSlash = s"$prefix/"

    def unapply(path: String): Option[Option[String]] =
      if (path == prefix) Some(None)
      else if (!path.startsWith(prefixSlash)) None
      else path.stripPrefix(prefixSlash) match {
        case "" => Some(None)
        case ns => Some(Some(ns))
      }
  }

  def versionString(buf: Buf): String = {
    val versionBytes = new Array[Byte](buf.length)
    buf.write(versionBytes, 0)
    Base64StringEncoder.encode(versionBytes)
  }
}

class HttpControlService(storage: DtabStore) extends Service[Request, Response] {
  import HttpControlService._

  /** Get the dtab, if it exists. */
  private[this] def getDtab(ns: String, composed: Boolean = false): Future[Option[VersionedDtab]] = {
    val obs = if (composed) storage.observeComposed(ns) else storage.observe(ns)
    obs.values.toFuture.flatMap(Future.const)
  }

  def apply(req: Request): Future[Response] = (req.path, req.method) match {
    case (DtabUri(None), _) =>
      handleList()
    case (DtabUri(Some(ns)), Method.Head) =>
      handleHeadDtab(ns, req)
    case (DtabUri(Some(ns)), Method.Get) =>
      handleGetDtab(ns, req)
    case (DtabUri(Some(ns)), Method.Put) =>
      handlePutDtab(ns, req)
    case (DtabUri(Some(ns)), Method.Post) =>
      handlePostDtab(ns, req)
    // invalid uri/method
    case _ =>
      Future.value(Response(Status.NotFound))
  }

  private[this] def handleList(): Future[Response] =
    storage.list().map { namespaces =>
      val rsp = Response()
      rsp.contentType = MediaType.Json
      rsp.content = Json.write(namespaces)
      rsp
    }

  private[this] def handleGetDtab(ns: String, req: Request): Future[Response] =
    getDtab(ns, req.getBooleanParam("composed")).map {
      case Some(dtab) =>
        val rsp = Response()
        val (contentType, codec) = DtabCodec.accept(req.accept).getOrElse(DtabCodec.default)
        rsp.contentType = contentType
        rsp.headerMap.add(Fields.Etag, versionString(dtab.version))
        rsp.content = codec.write(dtab.dtab)
        rsp

      case None => Response(Status.NotFound)
    }

  private[this] def handleHeadDtab(ns: String, req: Request): Future[Response] =
    getDtab(ns, req.getBooleanParam("composed")).map {
      case Some(dtab) =>
        val rsp = Response()
        rsp.headerMap.add(Fields.Etag, versionString(dtab.version))
        rsp

      case None => Response(Status.NotFound)
    }

  private[this] def handlePutDtab(ns: String, req: Request): Future[Response] =
    req.contentType.flatMap(DtabCodec.byContentType) match {
      case Some(codec) =>
        codec.read(req.content) match {
          case Return(dtab) =>
            req.headerMap.get(Fields.IfMatch) match {
              case Some(stamp) =>
                val buf = Buf.ByteArray.Owned(Base64StringEncoder.decode(stamp))
                storage.update(ns, dtab, buf).transform {
                  case Return(_) =>
                    Future.value(Response(Status.NoContent))
                  case Throw(e: DtabNamespaceDoesNotExist) =>
                    Future.value(Response(Status.NotFound))
                  case Throw(e: DtabVersionMismatchException) =>
                    Future.value(Response(Status.PreconditionFailed))
                  case Throw(_) =>
                    Future.value(Response(Status.InternalServerError))
                }
              case None =>
                storage.put(ns, dtab).map { _ =>
                  Response(Status.NoContent)
                }
            }

          // invalid dtab
          case Throw(_) => Future.value(Response(Status.BadRequest))
        }

      // invalid content type
      case None => Future.value(Response(Status.BadRequest))
    }

  private[this] def handlePostDtab(ns: String, req: Request): Future[Response] =
    req.contentType.flatMap(DtabCodec.byContentType) match {
      case Some(codec) =>
        codec.read(req.content) match {
          case Return(dtab) =>
            storage.create(ns, dtab).transform {
              case Return(_) => Future.value(Response(Status.NoContent))
              case Throw(_) => Future.value(Response(Status.InternalServerError))
            }

          // invalid dtab
          case Throw(_) => Future.value(Response(Status.BadRequest))
        }

      // invalid content type
      case None => Future.value(Response(Status.BadRequest))
    }
}
