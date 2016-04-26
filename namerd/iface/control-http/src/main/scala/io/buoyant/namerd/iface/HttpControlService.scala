package io.buoyant.namerd.iface

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.linkerd.admin.names.{DelegateApiHandler, Delegator}
import io.buoyant.namer.ConfiguredNamersInterpreter
import io.buoyant.namerd.DtabStore.{DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Forbidden}
import io.buoyant.namerd.{DtabCodec => DtabModule, DtabStore, Ns, RichActivity, VersionedDtab}

object HttpControlService {

  object Json {

    private[this] val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(DtabModule.module)

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

  val apiPrefix = "/api/1"

  object DtabUri {
    val prefix = s"$apiPrefix/dtabs"
    val prefixSlash = s"$prefix/"

    def unapply(request: Request): Option[(Method, Option[Ns])] =
      if (request.path == prefix) Some(request.method, None)
      else if (!request.path.startsWith(prefixSlash)) None
      else request.path.stripPrefix(prefixSlash) match {
        case "" => Some(request.method, None)
        case ns => Some(request.method, Some(ns))
      }
  }

  case class InvalidPathException(path: String, underlying: Exception)
    extends Exception(s"Invalid path: $path\n${underlying.getMessage}", underlying)

  trait NsPathUri {
    val prefix: String
    lazy val prefixSlash = s"$prefix/"

    def unapply(request: Request): Option[(Option[Ns], Path)] = {
      if (request.path.startsWith(prefix)) {
        val ns = if (request.path.startsWith(prefixSlash)) {
          Some(request.path.stripPrefix(prefixSlash))
        } else {
          None
        }
        val path = try {
          Path.read(request.getParam("path"))
        } catch {
          case e: IllegalArgumentException =>
            throw InvalidPathException(request.getParam("path"), e)
        }
        Some(ns, path)
      } else {
        None
      }
    }
  }

  object BindUri extends NsPathUri {
    val prefix = s"$apiPrefix/bind"
  }

  object AddrUri extends NsPathUri {
    val prefix = s"$apiPrefix/addr"
  }

  object DelegateUri extends NsPathUri {
    val prefix = s"$apiPrefix/delegate"
  }

  def versionString(buf: Buf): String = {
    val versionBytes = new Array[Byte](buf.length)
    buf.write(versionBytes, 0)
    Base64StringEncoder.encode(versionBytes)
  }

  private val DefaultNamer: (Path, Namer) = Path.empty -> Namer.global
}

class HttpControlService(storage: DtabStore, delegate: Ns => NameInterpreter, namers: Map[Path, Namer])
  extends Service[Request, Response] {
  import HttpControlService._

  /** Get the dtab, if it exists. */
  private[this] def getDtab(ns: String): Future[Option[VersionedDtab]] =
    storage.observe(ns).toFuture

  private[this] val delegateApiHander = new DelegateApiHandler(namers.toSeq)

  def apply(req: Request): Future[Response] = Future(req match {
    case DtabUri(_, None) =>
      handleList()
    case DtabUri(Method.Head, Some(ns)) =>
      handleHeadDtab(ns, req)
    case DtabUri(Method.Get, Some(ns)) =>
      handleGetDtab(ns, req)
    case DtabUri(Method.Put, Some(ns)) =>
      handlePutDtab(ns, req)
    case DtabUri(Method.Post, Some(ns)) =>
      handlePostDtab(ns, req)
    case DtabUri(Method.Delete, Some(ns)) =>
      handleDeleteDtab(ns)
    case BindUri(Some(ns), path) =>
      handleGetBind(ns, path)
    case AddrUri(Some(ns), path) =>
      handleGetAddr(ns, path)
    case DelegateUri(Some(ns), path) =>
      handleGetDelegate(ns, path)
    case DelegateUri(None, path) =>
      delegateApiHander(req)
    // invalid uri/method
    case _ =>
      Future.value(Response(Status.NotFound))
  }).flatten.handle {
    case Forbidden => Response(Status.Forbidden)
    case ex@InvalidPathException(path, _) =>
      val resp = Response(Status.BadRequest)
      resp.contentString = ex.getMessage
      resp
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
    // closable is a handle to the values observation so that we can close the observation when the
    // streaming connection is terminated
    @volatile var closable: Closable = Closable.nop
    // calls to writer.write must be flatMapped together to ensure proper ordering and backpressure
    // writeFuture is an accumulator of those flatMapped Futures
    @volatile var writeFuture: Future[Unit] = Future.Unit
    closable = values.respond { t =>
      writeFuture = writeFuture.before {
        val buf = render(t, closable)
        if (buf == Buf.Empty)
          Future.Unit
        else
          writer.write(buf).onFailure { _ => closable.close() }
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
          val act = delegate(ns).bind(Dtab.empty, path)
          bindingCache += (key -> act)
          act
      }
    }

  private[this] def renderTryTree(tryTree: Try[NameTree[Name.Bound]], closable: Closable) = tryTree match {
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
        val addr = bindAddrId(path).run.flatMap {
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

  private[this] def bindAddrId(id: Path): Activity[NameTree[Name.Bound]] = {
    val (pfx, namer) = namers.find { case (p, _) => id.startsWith(p) }.getOrElse(DefaultNamer)
    namer.bind(NameTree.Leaf(id.drop(pfx.size)))
  }

  private[this] val renderAddr = (addr: Addr, _: Closable) => addr match {
    case Addr.Bound(addrs, metadata) =>
      val bound = addrs.map {
        case Address.Inet(isa, meta) => isa.toString
        case a => a.toString
      }.mkString("Bound(", ",", ")\n")
      Buf.Utf8(bound)
    case Addr.Pending =>
      Buf.Empty
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
        DelegateApiHandler.getDelegateRsp(dtab.dtab.show, path.show, delegate(ns))
      case None => Future.value(Response(Status.NotFound))
    }
  }
}
