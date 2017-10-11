package io.buoyant.namerd.iface

import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namer.RichActivity
import io.buoyant.namerd.DtabStore.{DtabNamespaceAlreadyExistsException, DtabNamespaceDoesNotExistException, DtabVersionMismatchException, Forbidden}
import io.buoyant.namerd.{DtabStore, Ns, VersionedDtab}

object DtabUri {
  import HttpControlService._

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

class DtabHandler(storage: DtabStore) extends Service[Request, Response] {
  import HttpControlService._

  override def apply(req: Request): Future[Response] = (req match {
    case DtabUri(_, None) =>
      list(req)
    case DtabUri(Method.Head, Some(ns)) =>
      head(ns, req)
    case DtabUri(Method.Get, Some(ns)) =>
      get(ns, req)
    case DtabUri(Method.Put, Some(ns)) =>
      put(ns, req)
    case DtabUri(Method.Post, Some(ns)) =>
      post(ns, req)
    case DtabUri(Method.Delete, Some(ns)) =>
      delete(ns)
    case _ => NotFound
  }).handle {
    case Forbidden => Response(Status.Forbidden)
  }

  private[this] def renderList(list: Iterable[Ns]): Buf = Json.write(list).concat(newline)

  private[this] def list(req: Request): Future[Response] =
    if (isStreaming(req)) {
      streamingResp(storage.list().values, Some(MediaType.Json))(renderList)
    } else {
      storage.list().toFuture.map { namespaces =>
        val rsp = Response()
        rsp.contentType = MediaType.Json
        rsp.content = renderList(namespaces.toSeq.sorted)
        rsp
      }
    }

  private[this] def get(ns: String, req: Request): Future[Response] = {
    val (mediaType, codec) = DtabCodec.accept(req.acceptMediaTypes).getOrElse(DtabCodec.default)
    if (isStreaming(req)) {
      val dtabs: Event[Try[VersionedDtab]] = storage.observe(ns).values.collect {
        case Return(Some(dtab)) => Return(dtab)
        case Throw(e) => Throw(e)
      }
      streamingResp(dtabs, Some(mediaType)) { dtab =>
        codec.write(dtab.dtab).concat(newline)
      }
    } else {
      storage.observe(ns).toFuture.map {
        case Some(dtab) =>
          val rsp = Response()
          rsp.contentType = mediaType
          rsp.headerMap.add(Fields.Etag, DtabHandler.versionString(dtab.version))
          rsp.content = codec.write(dtab.dtab).concat(newline)
          rsp

        case None => Response(Status.NotFound)
      }
    }
  }

  private[this] def head(ns: String, req: Request): Future[Response] =
    storage.observe(ns).toFuture.map {
      case Some(dtab) =>
        val rsp = Response()
        rsp.headerMap.add(Fields.Etag, DtabHandler.versionString(dtab.version))
        rsp

      case None => Response(Status.NotFound)
    }

  private[this] def put(ns: String, req: Request): Future[Response] =
    req.mediaType.flatMap(DtabCodec.byMediaType) match {
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
          case Throw(e: IllegalArgumentException) =>
            val rsp = Response(Status.BadRequest)
            rsp.setContentString(e.getMessage)
            Future.value(rsp)
          case Throw(_) =>
            Future.value(Response(Status.BadRequest))
        }

      // invalid content type
      case None =>
        val rsp = Response(Status.BadRequest)
        rsp.setContentString(s"""Invalid Content-Type "${req.contentType.getOrElse("")}". """)
        Future.value(rsp)
    }

  private[this] def post(ns: String, req: Request): Future[Response] =
    req.mediaType.flatMap(DtabCodec.byMediaType) match {
      case Some(codec) =>
        codec.read(req.content) match {
          case Return(dtab) =>
            storage.create(ns, dtab).transform {
              case Return(_) =>
                Future.value(Response(Status.NoContent))
              case Throw(e: DtabNamespaceAlreadyExistsException) =>
                Future.value(Response(Status.Conflict))
              case Throw(_) =>
                Future.value(Response(Status.InternalServerError))
            }

          // invalid dtab
          case Throw(_) => Future.value(Response(Status.BadRequest))
        }

      // invalid content type
      case None => Future.value(Response(Status.BadRequest))
    }

  private[this] def delete(ns: String): Future[Response] =
    storage.delete(ns).transform {
      case Return(()) => Future.value(Response(Status.NoContent))
      case Throw(_: DtabNamespaceDoesNotExistException) => Future.value(Response(Status.NotFound))
      case Throw(_) => Future.value(Response(Status.InternalServerError))
    }
}

object DtabHandler {
  private[iface] def versionString(buf: Buf): String = {
    val versionBytes = new Array[Byte](buf.length)
    buf.write(versionBytes, 0)
    Base64StringEncoder.encode(versionBytes)
  }
}
