package io.buoyant.namerd.iface

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.linkerd.admin.names.{DelegateApiHandler, Delegator}
import io.buoyant.namerd.DtabStore.{DtabNamespaceDoesNotExistException, DtabVersionMismatchException}
import io.buoyant.namerd.{DtabStore, Ns, RichActivity, VersionedDtab}

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

  trait NsPathUri {
    val prefix: String

    def unapply(path: String): Option[(Ns, Path)] = {
      if (!path.startsWith(prefix)) return None
      val segments = path.stripPrefix(prefix).split("/")
      if (segments.size < 2) return None
      Some((segments.head, Path.Utf8(segments.drop(1): _*)))
    }
  }

  object BindUri extends NsPathUri {
    val prefix = "/api/1/bind/"
  }

  object AddrUri extends NsPathUri {
    val prefix = "/api/1/addr/"
  }

  object DelegateUri extends NsPathUri {
    val prefix = "/api/1/delegate/"
  }

  def versionString(buf: Buf): String = {
    val versionBytes = new Array[Byte](buf.length)
    buf.write(versionBytes, 0)
    Base64StringEncoder.encode(versionBytes)
  }
}

class HttpControlService(storage: DtabStore, namers: Ns => NameInterpreter)
  extends Service[Request, Response] {
  import HttpControlService._

  /** Get the dtab, if it exists. */
  private[this] def getDtab(ns: String): Future[Option[VersionedDtab]] =
    storage.observe(ns).toFuture

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
    case (DtabUri(Some(ns)), Method.Delete) =>
      handleDeleteDtab(ns)
    case (BindUri(ns, path), Method.Get) =>
      handleGetBind(ns, path)
    case (AddrUri(ns, path), Method.Get) =>
      handleGetAddr(ns, path)
    case (DelegateUri(ns, path), Method.Get) =>
      handleGetDelegate(ns, path)
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
    getDtab(ns).map {
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
    getDtab(ns).map {
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
                  case Throw(e: DtabNamespaceDoesNotExistException) =>
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

  private[this] def handleDeleteDtab(ns: String): Future[Response] =
    storage.delete(ns).transform {
      case Return(()) => Future.value(Response(Status.NoContent))
      case Throw(_: DtabNamespaceDoesNotExistException) => Future.value(Response(Status.NotFound))
      case Throw(_) => Future.value(Response(Status.InternalServerError))
    }

  private[this] def streamingResp[T](values: Event[T], render: (T, Closable) => Buf): Future[Response] = {
    val resp = Response()
    resp.setChunked(true)
    val writer = resp.writer
    var closable: Closable = null
    var writeFuture: Future[Unit] = Future.Unit
    closable = values.respond { t =>
      writeFuture = writeFuture.before {
        writer.write(render(t, closable)).onFailure { _ =>
          closable.close()
        }
      }
    }
    Future.value(resp)
  }

  private[this] val bindingCacheMu = new {}
  private[this] var bindingCache: Map[(String, Path), Activity[NameTree[Name.Bound]]] = Map.empty
  private[this] def getBind(ns: String, path: Path): Activity[NameTree[Name.Bound]] =
    bindingCacheMu.synchronized {
      val key = (ns, path)
      bindingCache.get(key) match {
        case Some(act) => act
        case None =>
          val act = namers(ns).bind(Dtab.empty, path)
          bindingCache += (key -> act)
          act
      }
    }

  private[this] val renderTryTree =
    (tryTree: Try[NameTree[Name.Bound]], closable: Closable) => tryTree match {
      case Return(tree) =>
        Buf.Utf8(tree.show + "\n")
      case Throw(e) =>
        closable.close()
        Buf.Empty
    }

  private[this] def handleGetBind(ns: String, path: Path): Future[Response] = {
    val act = getBind(ns, path)
    streamingResp(act.values, renderTryTree)
  }

  private[this] val addrCacheMu = new {}
  private[this] var addrCache: Map[(String, Path), Var[Addr]] = Map.empty
  private[this] def getAddr(ns: String, path: Path): Var[Addr] = addrCacheMu.synchronized {
    val key = (ns, path)
    addrCache.get(key) match {
      case Some(addr) => addr
      case None =>
        val addr = getBind(ns, path).run.flatMap {
          case Activity.Pending => Var.value(Addr.Pending)
          case Activity.Failed(e) => Var.value(Addr.Failed(e))
          case Activity.Ok(tree) => tree match {
            case NameTree.Leaf(bound) => bound.addr
            case NameTree.Empty => Var.value(Addr.Bound())
            case NameTree.Fail => Var.value(Addr.Failed("name tree failed"))
            case NameTree.Neg => Var.value(Addr.Neg)
            case NameTree.Alt(_) | NameTree.Union(_) =>
              Var.value(Addr.Failed(s"${path.show} is not a concrete bound id"))
          }
        }
        addrCache += (key -> addr)
        addr
    }
  }

  private[this] val renderAddr = (addr: Addr, _: Closable) => addr match {
    case Addr.Bound(addrs, metadata) =>
      val bound = addrs.map {
        case Address.Inet(isa, meta) => isa.toString
        case a => a.toString
      }.mkString("Bound(", ",", ")\n")
      Buf.Utf8(bound)
    case _ =>
      Buf.Utf8(addr.toString + "\n")
  }

  private[this] def handleGetAddr(ns: String, path: Path): Future[Response] = {
    val addr = getAddr(ns, path)
    streamingResp(addr.changes, renderAddr)
  }

  private[this] def handleGetDelegate(ns: String, path: Path): Future[Response] = {
    getDtab(ns).flatMap {
      case Some(dtab) =>
        Delegator(dtab.dtab, path, namers(ns)).toFuture.map { delegateTree =>
          val rsp = Response()
          rsp.content = DelegateApiHandler.Codec.writeBuf(delegateTree)
          rsp.contentType = MediaType.Json
          rsp
        }
      case None => Future.value(Response(Status.NotFound))
    }
  }
}
